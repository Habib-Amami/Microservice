#!/usr/bin/env bash
# Run the whole local stack.
#
#   scripts/run-stack.sh start [--frontend]
#   scripts/run-stack.sh stop
#   scripts/run-stack.sh restart
#   scripts/run-stack.sh status
#   scripts/run-stack.sh logs <component>
#   scripts/run-stack.sh seed [--force]
#   scripts/run-stack.sh reset            # wipe service databases, reseed
#
# MySQL runs in a Docker container (default port 3307 so a system MySQL on
# 3306 is left alone); Config Server, Eureka, Gateway and the six services
# run with their Maven wrappers. Logs and PIDs go to .run/.
#
# Environment overrides:
#   DB_PORT=3307  DB_USERNAME=root  DB_PASSWORD=root
#   MYSQL_CONTAINER=stack-mysql  SKIP_DB=1 (use an existing MySQL)
#   JAVA_HOME (Java 21+ required)

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT/.run"
LOG_DIR="$RUN_DIR/logs"
PID_DIR="$RUN_DIR/pids"

DB_PORT="${DB_PORT:-3307}"
DB_USERNAME="${DB_USERNAME:-root}"
DB_PASSWORD="${DB_PASSWORD:-root}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-stack-mysql}"
SKIP_DB="${SKIP_DB:-0}"
GATEWAY_URL="http://localhost:8080/api"

names=(config-server discovery-server api-gateway
       product-service inventory-service supplier-service
       user-service procurement-service payment-service)
dirs=(infrastructure/config-server infrastructure/discovery-server infrastructure/api-gateway
      product-service inventory-service supplier-service
      user-service procurement-service payment-service)
# Infrastructure modules share the wrapper one level up.
runners=(../mvnw ../mvnw ../mvnw
         ./mvnw ./mvnw ./mvnw ./mvnw ./mvnw ./mvnw)
ports=(8888 8761 8080 8082 8083 8084 8085 8086 8087)
dbs=(- - - product_db inventory_db supplier_db user_db procurement_db payment_db)

die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
info() { printf '%s\n' "$*"; }

java_major() {
  java -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -1
}

check_java() {
  command -v java >/dev/null 2>&1 || die "Java is not on PATH (Java 21+ required)."
  local major; major="$(java_major)"
  [ -n "$major" ] && [ "$major" -ge 21 ] \
    || die "Java 21+ required, found: $(java -version 2>&1 | head -1)"
}

port_busy() { ss -tln 2>/dev/null | awk '{print $4}' | grep -qE "[:.]$1\$"; }

require_free_ports() {
  local busy=()
  for port in "${ports[@]}"; do
    port_busy "$port" && busy+=("$port")
  done
  if [ ${#busy[@]} -gt 0 ]; then
    die "ports already in use: ${busy[*]}. Run 'scripts/run-stack.sh stop' or free them."
  fi
}

ensure_mysql() {
  [ "$SKIP_DB" = "1" ] && { info "SKIP_DB=1: using MySQL at localhost:$DB_PORT"; return; }
  command -v docker >/dev/null 2>&1 || die "Docker is required (or set SKIP_DB=1 for an existing MySQL)."

  if docker ps --format '{{.Names}}' | grep -qx "$MYSQL_CONTAINER"; then
    info "MySQL container '$MYSQL_CONTAINER' already running."
  elif docker ps -a --format '{{.Names}}' | grep -qx "$MYSQL_CONTAINER"; then
    info "Starting MySQL container '$MYSQL_CONTAINER' ..."
    docker start "$MYSQL_CONTAINER" >/dev/null
  else
    info "Creating MySQL 8.4 container '$MYSQL_CONTAINER' on port $DB_PORT ..."
    docker run -d --name "$MYSQL_CONTAINER" \
      -e MYSQL_ROOT_PASSWORD="$DB_PASSWORD" \
      -p "$DB_PORT:3306" mysql:8.4 >/dev/null
  fi

  local mapped
  mapped="$(docker port "$MYSQL_CONTAINER" 3306/tcp 2>/dev/null | head -1 | sed 's/.*://')"
  if [ -n "$mapped" ] && [ "$mapped" != "$DB_PORT" ]; then
    die "container '$MYSQL_CONTAINER' publishes MySQL on port $mapped, but DB_PORT=$DB_PORT.
       Run with DB_PORT=$mapped, or recreate the container on the port you want."
  fi

  for _ in $(seq 1 60); do
    if docker exec "$MYSQL_CONTAINER" mysqladmin ping -uroot -p"$DB_PASSWORD" --silent >/dev/null 2>&1; then
      info "MySQL is ready on localhost:$DB_PORT"
      return
    fi
    sleep 1
  done
  die "MySQL did not become ready. Inspect: docker logs $MYSQL_CONTAINER"
}

start_one() {
  local name="$1" dir="$2" runner="$3"; shift 3
  local -a envargs=("$@")
  mkdir -p "$LOG_DIR" "$PID_DIR"
  rm -f "$LOG_DIR/$name.log"
  (
    cd "$ROOT/$dir"
    setsid env "${envargs[@]}" "$runner" -B spring-boot:run >"$LOG_DIR/$name.log" 2>&1 &
    echo $! >"$PID_DIR/$name.pid"
  )
}

service_env() {
  local db="${1:--}"
  local -a envargs=(
    CONFIG_SERVER_URL=http://localhost:8888
    EUREKA_CLIENT_SERVICE_URL_DEFAULT_ZONE=http://localhost:8761/eureka/
  )
  if [ "$db" != "-" ]; then
    local prefix="${db%_db}"
    prefix="${prefix^^}"
    envargs+=("${prefix}_DB_URL=jdbc:mysql://localhost:${DB_PORT}/${db}?createDatabaseIfNotExist=true&serverTimezone=UTC")
    envargs+=("${prefix}_DB_USERNAME=${DB_USERNAME}")
    envargs+=("${prefix}_DB_PASSWORD=${DB_PASSWORD}")
  fi
  printf '%s\n' "${envargs[@]}"
}

pid_of() { cat "$PID_DIR/$1.pid" 2>/dev/null || true; }

alive() {
  local pid; pid="$(pid_of "$1")"
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

wait_log() {
  local name="$1" pattern="$2" tries="${3:-120}"
  for _ in $(seq 1 "$tries"); do
    if grep -q "$pattern" "$LOG_DIR/$name.log" 2>/dev/null; then
      info "  $name started"
      return 0
    fi
    if ! alive "$name"; then
      tail -n 20 "$LOG_DIR/$name.log" 2>/dev/null || true
      die "$name exited during startup (log: .run/logs/$name.log)"
    fi
    sleep 2
  done
  tail -n 25 "$LOG_DIR/$name.log" 2>/dev/null || true
  die "$name did not report startup (log: .run/logs/$name.log)"
}

wait_http() {
  local name="$1" url="$2" tries="${3:-90}"
  for _ in $(seq 1 "$tries"); do
    if curl -fsS -m 2 -o /dev/null "$url" 2>/dev/null; then
      info "  $name ready"
      return 0
    fi
    alive "$name" || { tail -n 15 "$LOG_DIR/$name.log" 2>/dev/null || true; die "$name exited during startup (log: .run/logs/$name.log)"; }
    sleep 2
  done
  tail -n 25 "$LOG_DIR/$name.log" 2>/dev/null || true
  die "$name did not become ready (log: .run/logs/$name.log)"
}

wait_tcp() {
  local name="$1" port="$2" tries="${3:-120}"
  for _ in $(seq 1 "$tries"); do
    if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
      exec 3>&- 2>/dev/null || true
      return 0
    fi
    alive "$name" || { tail -n 15 "$LOG_DIR/$name.log" 2>/dev/null || true; die "$name exited during startup (log: .run/logs/$name.log)"; }
    sleep 2
  done
  die "$name did not open port $port (log: .run/logs/$name.log)"
}

wait_route() {
  local tries="${1:-90}" code
  for _ in $(seq 1 "$tries"); do
    code="$(curl -s -m 2 -o /dev/null -w '%{http_code}' "$GATEWAY_URL/produits" 2>/dev/null || true)"
    if [ "$code" = "200" ]; then
      info "  gateway is routing"
      return 0
    fi
    sleep 2
  done
  die "gateway cannot reach the services yet (last HTTP ${code:-000}); check Eureka and .run/logs/api-gateway.log"
}

cmd_start() {
  local frontend=0
  for arg in "$@"; do
    case "$arg" in
      --frontend) frontend=1 ;;
      *) die "unknown start option: $arg" ;;
    esac
  done

  check_java
  require_free_ports
  rm -f "$PID_DIR"/*.pid 2>/dev/null || true
  ensure_mysql

  info "Starting configuration and discovery ..."
  start_one config-server infrastructure/config-server ../mvnw
  wait_log config-server 'Started ConfigServerApplication'
  wait_http config-server http://localhost:8888/discovery-server/default

  start_one discovery-server infrastructure/discovery-server ../mvnw \
    CONFIG_SERVER_URL=http://localhost:8888 \
    EUREKA_CLIENT_SERVICE_URL_DEFAULT_ZONE=http://localhost:8761/eureka/
  wait_log discovery-server 'Started DiscoveryServerApplication'
  wait_http discovery-server http://localhost:8761/

  info "Starting gateway and services ..."
  start_one api-gateway infrastructure/api-gateway ../mvnw \
    CONFIG_SERVER_URL=http://localhost:8888 \
    EUREKA_CLIENT_SERVICE_URL_DEFAULT_ZONE=http://localhost:8761/eureka/
  for i in "${!names[@]}"; do
    [ "${dbs[$i]}" = "-" ] && continue
    mapfile -t envargs < <(service_env "${dbs[$i]}")
    start_one "${names[$i]}" "${dirs[$i]}" "${runners[$i]}" "${envargs[@]}"
  done

  wait_tcp api-gateway 8080
  info "  api-gateway open on 8080 (registration completes shortly)"
  for i in "${!names[@]}"; do
    [ "${dbs[$i]}" = "-" ] && continue
    wait_http "${names[$i]}" "http://localhost:${ports[$i]}/actuator/health"
  done
  wait_route

  if [ "$frontend" = "1" ]; then
    info "Starting Angular UI ..."
    mkdir -p "$LOG_DIR" "$PID_DIR"
    ( cd "$ROOT/frontend" && setsid npm start >"$LOG_DIR/frontend.log" 2>&1 & echo $! >"$PID_DIR/frontend.pid" )
    wait_tcp frontend 4200
    info "  UI on http://localhost:4200"
  fi

  info ""
  info "Stack is up. Gateway API: $GATEWAY_URL"
  info "Seed demo data:  scripts/run-stack.sh seed"
  info "Status:          scripts/run-stack.sh status"
}

cmd_stop() {
  info "Stopping stack ..."
  local order=(frontend payment-service procurement-service user-service
               supplier-service inventory-service product-service api-gateway
               discovery-server config-server)
  for name in "${order[@]}"; do
    local pid; pid="$(pid_of "$name")"
    [ -z "$pid" ] && continue
    if kill -0 "$pid" 2>/dev/null; then
      if [ ! -r "/proc/$pid/cmdline" ] \
         || ! grep -qa 'spring-boot:run\|npm' "/proc/$pid/cmdline" 2>/dev/null; then
        info "  skipping $name (pid $pid no longer belongs to this stack)"
        rm -f "$PID_DIR/$name.pid"
        continue
      fi
      kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
      for _ in $(seq 1 30); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
      kill -KILL -- "-$pid" 2>/dev/null || true
      info "  stopped $name"
    fi
    rm -f "$PID_DIR/$name.pid"
  done
  info "MySQL container left running (docker stop $MYSQL_CONTAINER to stop it)."
}

cmd_status() {
  printf '%-20s %-6s %-10s %s\n' COMPONENT PORT PID STATE
  for i in "${!names[@]}"; do
    local state="down"
    if alive "${names[$i]}"; then
      if [ "${names[$i]}" = "api-gateway" ]; then
        port_busy "${ports[$i]}" && state="listening"
      else
        local url="http://localhost:${ports[$i]}/actuator/health"
        [ "${names[$i]}" = "discovery-server" ] && url="http://localhost:${ports[$i]}/"
        local code; code="$(curl -s -m 2 -o /dev/null -w '%{http_code}' "$url" 2>/dev/null || true)"
        [ "$code" = "200" ] && state="UP" || state="starting (HTTP ${code:-000})"
      fi
    fi
    printf '%-20s %-6s %-10s %s\n' "${names[$i]}" "${ports[$i]}" "$(pid_of "${names[$i]}")" "$state"
  done
  alive frontend && printf '%-20s %-6s %-10s %s\n' frontend 4200 "$(pid_of frontend)" listening
  printf 'MySQL: '
  if [ "$SKIP_DB" = "1" ]; then
    info "external on localhost:$DB_PORT"
  elif docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$MYSQL_CONTAINER"; then
    info "$MYSQL_CONTAINER on localhost:$DB_PORT"
  else
    info "not running"
  fi
}

cmd_logs() {
  local name="${1:-}"
  [ -z "$name" ] && { info "components: ${names[*]} frontend"; return; }
  [ -f "$LOG_DIR/$name.log" ] || die "no log for '$name' (.run/logs/$name.log)"
  tail -n 200 -f "$LOG_DIR/$name.log"
}

cmd_seed() {
  wait_route 15
  python3 "$ROOT/scripts/seed-demo.py" "$@"
}

cmd_reset() {
  wait_route 15
  docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$MYSQL_CONTAINER" \
    || die "MySQL container '$MYSQL_CONTAINER' is not running."
  info "Wiping service databases ..."
  for db in product_db inventory_db supplier_db user_db procurement_db payment_db; do
    local tables sql
    tables="$(docker exec "$MYSQL_CONTAINER" mysql -u"$DB_USERNAME" -p"$DB_PASSWORD" -N \
      -e "SELECT table_name FROM information_schema.tables WHERE table_schema='$db' AND table_name<>'flyway_schema_history'" 2>/dev/null || true)"
    [ -z "$tables" ] && continue
    sql="SET FOREIGN_KEY_CHECKS=0;"
    for t in $tables; do sql="$sql TRUNCATE TABLE \`$t\`;"; done
    sql="$sql SET FOREIGN_KEY_CHECKS=1;"
    docker exec "$MYSQL_CONTAINER" mysql -u"$DB_USERNAME" -p"$DB_PASSWORD" -e "USE $db; $sql" 2>/dev/null
  done
  python3 "$ROOT/scripts/seed-demo.py" "$@"
}

usage() {
  cat <<'EOF'
Local stack lifecycle: MySQL (Docker), Config Server, Eureka, Gateway,
the six services, and optionally the Angular UI.

  scripts/run-stack.sh start [--frontend]
  scripts/run-stack.sh stop
  scripts/run-stack.sh restart
  scripts/run-stack.sh status
  scripts/run-stack.sh logs <component>
  scripts/run-stack.sh seed [--force]
  scripts/run-stack.sh reset            # wipe service databases, reseed

Environment overrides:
  DB_PORT=3307  DB_USERNAME=root  DB_PASSWORD=root
  MYSQL_CONTAINER=stack-mysql  SKIP_DB=1 (use an existing MySQL)

Logs: .run/logs/<component>.log
EOF
}

cmd="${1:-help}"
if [ $# -gt 0 ]; then shift; fi
case "$cmd" in
  start)   cmd_start "$@" ;;
  stop)    cmd_stop ;;
  restart) cmd_stop; cmd_start "$@" ;;
  status)  cmd_status ;;
  logs)    cmd_logs "$@" ;;
  seed)    cmd_seed "$@" ;;
  reset)   cmd_reset "$@" ;;
  help|-h|--help) usage ;;
  *) die "unknown command '$cmd' (try: help)" ;;
esac

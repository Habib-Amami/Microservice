#!/usr/bin/env bash
# Demonstrate and verify client-side load balancing between service instances.
#
#   scripts/load-balance-check.sh [options]
#
# Options:
#   --service NAME    service to scale (default: product-service)
#   --replicas N      number of instances (default: 2)
#   --requests N      number of Gateway GET requests (default: 20)
#   --path PATH       Gateway-relative GET path (default: derived from --service)
#   --keep            leave the scaled stack running instead of restoring base
#
# What it does:
#   1. Stops the base stack and restarts it with compose.lb.yml, which removes
#      the fixed container names/host ports and turns on Tomcat access logs.
#   2. Scales the chosen service, waits for health and checks that Eureka sees
#      the expected number of instances.
#   3. Sends N GET requests through the Gateway (lb:// routes round-robin) with
#      a unique token in the query string.
#   4. Counts matching access-log lines inside each replica container and
#      reports the per-instance distribution.
#   5. Restores the normal single-instance stack unless --keep is given.
#
# Requires docker compose >= 2.24 (for the !reset YAML tag), curl and python3.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

BASE=(-f compose.yaml)
LB=(-f compose.yaml -f compose.lb.yml)

SERVICE="product-service"
REPLICAS=2
REQUESTS=20
PATH_ARG=""
KEEP=0

info() { printf '\033[1;34m[lb]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[lb] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --service)   SERVICE="${2:?--service needs a value}"; shift 2 ;;
    --replicas)  REPLICAS="${2:?--replicas needs a value}"; shift 2 ;;
    --requests)  REQUESTS="${2:?--requests needs a value}"; shift 2 ;;
    --path)      PATH_ARG="${2:?--path needs a value}"; shift 2 ;;
    --keep)      KEEP=1; shift ;;
    -h|--help)   sed -n '2,24p' "$0"; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
done

[[ "$REPLICAS" =~ ^[0-9]+$ ]] && [ "$REPLICAS" -ge 2 ] || die "--replicas must be >= 2"
[[ "$REQUESTS" =~ ^[0-9]+$ ]] && [ "$REQUESTS" -ge 1 ] || die "--requests must be >= 1"

# Gateway-relative paths for the services that can be scaled.
case "$SERVICE" in
  product-service)    DEFAULT_PATH=/api/produits ;;
  inventory-service)  DEFAULT_PATH=/api/stocks ;;
  supplier-service)   DEFAULT_PATH=/api/fournisseurs ;;
  user-service)       DEFAULT_PATH=/api/operateurs ;;
  procurement-service) DEFAULT_PATH=/api/facture/retrieve-all-factures ;;
  payment-service)    DEFAULT_PATH=/api/reglements ;;
  *) die "unsupported --service '$SERVICE' (expected one of the six business services)" ;;
esac
REQ_PATH="${PATH_ARG:-$DEFAULT_PATH}"

restore_base() {
  info "Restoring the normal single-instance stack ..."
  docker compose "${LB[@]}" down >/dev/null 2>&1 || true
  docker compose "${BASE[@]}" up -d --wait >/dev/null 2>&1 || \
    printf 'WARNING: base stack did not become healthy; run scripts/compose-stack.sh status\n' >&2
}
if [ "$KEEP" -eq 0 ]; then trap restore_base EXIT; fi

TOKEN="lbcheck-$(date +%s)-$$"

info "Stopping the base stack (volumes are kept) ..."
docker compose "${LB[@]}" down >/dev/null 2>&1 || true

info "Starting stack with $SERVICE x$REPLICAS ..."
docker compose "${LB[@]}" up -d --build --wait --scale "$SERVICE=$REPLICAS"

mapfile -t CONTAINERS < <(docker compose "${LB[@]}" ps -q "$SERVICE" | sort -u)
[ "${#CONTAINERS[@]}" -eq "$REPLICAS" ] || \
  die "expected $REPLICAS containers for $SERVICE, found ${#CONTAINERS[@]}"

# Eureka registration check (JSON REST API). Retry while instances register.
EUREKA_APP="$(printf '%s' "$SERVICE" | tr '[:lower:]' '[:upper:]')"
info "Checking Eureka registry for $EUREKA_APP ..."
REGISTERED=0
for _ in $(seq 1 30); do
  REGISTERED=$(curl -fsS -H 'Accept: application/json' \
    "http://localhost:8761/eureka/apps/$EUREKA_APP" 2>/dev/null | python3 -c '
import json, sys
try:
    data = json.load(sys.stdin)
except Exception:
    print(0); raise SystemExit
inst = data.get("application", {}).get("instance", [])
print(len(inst) if isinstance(inst, list) else (1 if inst else 0))
' 2>/dev/null || echo 0)
  [ "$REGISTERED" -ge "$REPLICAS" ] && break
  sleep 2
done
[ "$REGISTERED" -ge "$REPLICAS" ] || \
  die "Eureka sees $REGISTERED instance(s) of $EUREKA_APP; expected >= $REPLICAS"
info "Eureka reports $REGISTERED registered instances."

# The Gateway caches Eureka's registry and refreshes it periodically, so a
# just-started stack can still return 503 for a few seconds after all health
# checks pass. Wait until a real routed request succeeds before measuring.
info "Waiting for the Gateway to pick up the service instances ..."
READY=0
for _ in $(seq 1 45); do
  code=$(curl -sS -o /dev/null -w '%{http_code}' \
    "http://localhost:8080${REQ_PATH}" 2>/dev/null || echo 000)
  case "$code" in
    2??) READY=1; break ;;
    *) sleep 2 ;;
  esac
done
[ "$READY" -eq 1 ] || die "Gateway never routed $REQ_PATH successfully; check gateway logs"

info "Sending $REQUESTS GET requests through the Gateway to $REQ_PATH ..."
FAILED=0
for _ in $(seq 1 "$REQUESTS"); do
  code=$(curl -sS -o /dev/null -w '%{http_code}' \
    "http://localhost:8080${REQ_PATH}?lbtoken=${TOKEN}" || echo 000)
  case "$code" in
    2??) ;;
    *) FAILED=$((FAILED + 1)) ;;
  esac
done
[ "$FAILED" -eq 0 ] || printf 'WARNING: %s/%s requests returned a non-2xx status.\n' "$FAILED" "$REQUESTS" >&2

# Access-log lines are flushed asynchronously, so poll until all of our
# uniquely tagged requests have appeared before judging the distribution.
info "Counting access-log hits per replica ..."
TOKEN_PATTERN="lbtoken=${TOKEN}"
TOTAL=0
COUNTS=()
for _ in $(seq 1 15); do
  TOTAL=0
  COUNTS=()
  for cid in "${CONTAINERS[@]}"; do
    count=$(docker exec "$cid" sh -c 'cat /tmp/access/* 2>/dev/null' 2>/dev/null | \
      grep -c "$TOKEN_PATTERN" || true)
    COUNTS+=("$count")
    TOTAL=$((TOTAL + count))
  done
  [ "$TOTAL" -ge "$REQUESTS" ] && break
  sleep 1
done

for i in "${!CONTAINERS[@]}"; do
  printf '  %s  %3s request(s)\n' \
    "$(docker ps --no-trunc --format '{{.Names}}' -f "id=${CONTAINERS[$i]}")" \
    "${COUNTS[$i]}"
done

printf '\n'
info "Reported by logs: $TOTAL / $REQUESTS requests."
if [ "$TOTAL" -gt 0 ]; then
  hit=0
  for count in "${COUNTS[@]}"; do [ "$count" -gt 0 ] && hit=$((hit + 1)); done
  if [ "$hit" -ge 2 ]; then
    info "PASS: traffic reached $hit distinct instances -> load balancing verified."
  else
    die "only $hit instance(s) served requests; load balancing NOT verified"
  fi
else
  die "no access-log lines matched; load balancing NOT verified"
fi

if [ "$KEEP" -eq 0 ]; then
  restore_base
  trap - EXIT
  info "Base stack restored. Use --keep to leave the scaled stack running."
else
  trap - EXIT
  info "Scaled stack left running. Restore with: docker compose -f compose.yaml -f compose.lb.yml down && docker compose up -d"
fi

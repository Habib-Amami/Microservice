#!/usr/bin/env bash
# Small wrapper around Docker Compose for the full local demo stack.
# The underlying `docker compose` commands still work directly.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

usage() {
  cat <<'EOF'
Full Docker Compose stack (application + observability):

  scripts/compose-stack.sh up            # build and start everything
  scripts/compose-stack.sh quality       # also start SonarQube + Nexus
  scripts/compose-stack.sh status        # container health
  scripts/compose-stack.sh seed          # load the demo dataset
  scripts/compose-stack.sh logs [name]   # follow logs (all or one service)
  scripts/compose-stack.sh down          # stop containers, keep data
  scripts/compose-stack.sh reset         # stop and delete all volumes
  scripts/compose-stack.sh urls          # print the demo URLs

Any extra arguments are forwarded to docker compose / the seeder.
EOF
}

cmd="${1:-help}"
if [ $# -gt 0 ]; then shift; fi

case "$cmd" in
  up)      docker compose up -d --build "$@" ;;
  quality) docker compose --profile quality up -d "$@" ;;
  status)  docker compose ps "$@" ;;
  seed)    python3 scripts/seed-demo.py "$@" ;;
  logs)    docker compose logs -f "$@" ;;
  down)    docker compose down "$@" ;;
  reset)   docker compose down -v "$@" ;;
  urls)
    cat <<'EOF'
UI / API           http://localhost:4200   Angular app (proxies /api -> gateway)
Gateway API        http://localhost:8080/api
Eureka             http://localhost:8761
Swagger (product)  http://localhost:8082/swagger-ui.html
Grafana            http://localhost:3000   admin / admin (or .env)
Zipkin             http://localhost:9411
Axon Server        http://localhost:8024
Prometheus         http://localhost:9090
SonarQube          http://localhost:9000   (quality profile)
Nexus              http://localhost:8081   (quality profile)
MySQL              localhost:3307          root / root (or .env)
EOF
    ;;
  help|-h|--help) usage ;;
  *) printf 'unknown command: %s\n\n' "$cmd" >&2; usage; exit 1 ;;
esac

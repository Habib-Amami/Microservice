#!/usr/bin/env bash
# Remove only regenerable, gitignored build/run artifacts.
# Source files, migration docs, service folders and Docker/CI paths are never touched.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

DRY_RUN=0
if [ "${1:-}" = "--dry-run" ]; then
  DRY_RUN=1
elif [ -n "${1:-}" ]; then
  echo "usage: scripts/clean-generated.sh [--dry-run]" >&2
  exit 1
fi

# Explicit allowlist: generated output only. Nothing here is tracked by Git.
PATHS=(
  infrastructure/api-gateway/target
  infrastructure/config-server/target
  infrastructure/discovery-server/target
  services/product-service/target
  services/inventory-service/target
  services/supplier-service/target
  services/user-service/target
  services/procurement-service/target
  services/payment-service/target
  frontend/node_modules
  frontend/dist
  frontend/.angular
  .run/logs
  .run/pids
  scripts/__pycache__
)

removed=0
for path in "${PATHS[@]}"; do
  if [ -e "$path" ]; then
    size="$(du -sh "$path" 2>/dev/null | cut -f1 || echo '?')"
    if [ "$DRY_RUN" -eq 1 ]; then
      printf 'would remove %-42s %s\n' "$path" "$size"
    else
      rm -rf -- "$path"
      printf 'removed     %-42s %s\n' "$path" "$size"
    fi
    removed=$((removed + 1))
  fi
done

if [ "$DRY_RUN" -eq 1 ]; then
  echo "Dry run: $removed artifact path(s) would be removed."
else
  echo "Cleaned $removed artifact path(s). Rebuild with ./mvnw -B -ntp verify per module and npm ci in frontend/."
fi

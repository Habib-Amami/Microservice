#!/usr/bin/env bash
# Package every Maven module and publish its snapshot to Nexus.
#
#   docker compose --profile quality up -d nexus
#   # first login: docker exec achat-nexus cat /nexus-data/admin.password
#   NEXUS_USER=admin NEXUS_PASSWORD='<password>' scripts/publish-nexus.sh
#
# Environment:
#   NEXUS_URL     default http://localhost:8081
#   NEXUS_REPO    default maven-snapshots
#   NEXUS_USER / NEXUS_PASSWORD  Nexus credentials (required)

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NEXUS_URL="${NEXUS_URL:-http://localhost:8081}"
NEXUS_REPO="${NEXUS_REPO:-maven-snapshots}"
NEXUS_USER="${NEXUS_USER:-}"
NEXUS_PASSWORD="${NEXUS_PASSWORD:-}"

if [ -z "$NEXUS_USER" ] || [ -z "$NEXUS_PASSWORD" ]; then
  printf 'ERROR: set NEXUS_USER and NEXUS_PASSWORD before publishing.\n' >&2
  exit 1
fi

modules=(infrastructure infrastructure/config-server infrastructure/discovery-server
         infrastructure/api-gateway services/product-service services/inventory-service
         services/supplier-service services/user-service services/procurement-service
         services/payment-service)
deploy_url="${NEXUS_URL%/}/repository/${NEXUS_REPO}/"
printf 'Publishing to %s\n' "$deploy_url"

failures=0
for module in "${modules[@]}"; do
  printf '\n=== %s ===\n' "$module"
  case "$module" in
    infrastructure/*) runner="../mvnw" ;;
    *)                runner="./mvnw" ;;
  esac

  if [ "$module" = "infrastructure" ]; then
    # The aggregator POM must be in Nexus so its children resolve during a
    # clean build on another machine.
    file="pom.xml"
    extra=(-Dpackaging=pom)
  else
    ( cd "$ROOT/$module" && "$runner" -B -ntp -Dmaven.test.skip=true package ) \
      || { failures=$((failures + 1)); continue; }
    file="$(cd "$ROOT/$module" && find target -maxdepth 1 -type f -name '*.jar' \
              ! -name '*-sources.jar' ! -name '*-javadoc.jar' | head -1)"
    if [ -z "$file" ]; then
      printf 'ERROR: no jar produced for %s\n' "$module" >&2
      failures=$((failures + 1))
      continue
    fi
    extra=()
  fi

  ( cd "$ROOT/$module" && "$runner" -B -ntp org.apache.maven.plugins:maven-deploy-plugin:3.1.4:deploy-file \
      "-Durl=$deploy_url" \
      -DrepositoryId=nexus \
      "-Dfile=$file" \
      -DpomFile=pom.xml \
      -DgeneratePom=false \
      "-Dusername=$NEXUS_USER" \
      "-Dpassword=$NEXUS_PASSWORD" \
      "${extra[@]}" ) || failures=$((failures + 1))
done

if [ "$failures" -gt 0 ]; then
  printf '\n%d module(s) failed to publish.\n' "$failures" >&2
  exit 1
fi
printf '\nAll snapshots published. Browse %s\n' "${NEXUS_URL%/}/"

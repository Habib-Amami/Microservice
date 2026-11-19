#!/usr/bin/env bash
# Analyse every Maven module with a running SonarQube instance.
#
#   docker compose --profile quality up -d sonarqube
#   SONAR_TOKEN=... scripts/sonar-scan.sh
#
# Environment:
#   SONAR_URL          default http://localhost:9000
#   SONAR_TOKEN        token from SonarQube -> My Account -> Security
#   SONAR_PROJECT_PREFIX  default achat (project keys are achat-<module>)

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SONAR_URL="${SONAR_URL:-http://localhost:9000}"
SONAR_TOKEN="${SONAR_TOKEN:-${SONAR_LOGIN:-}}"
PREFIX="${SONAR_PROJECT_PREFIX:-achat}"
PLUGIN="org.sonarsource.scanner.maven:sonar-maven-plugin:5.9.0.7382:sonar"

modules=(infrastructure/config-server infrastructure/discovery-server
         infrastructure/api-gateway services/product-service services/inventory-service
         services/supplier-service services/user-service services/procurement-service
         services/payment-service)
runners=(../mvnw ../mvnw ../mvnw ./mvnw ./mvnw ./mvnw ./mvnw ./mvnw ./mvnw)

if [ -z "$SONAR_TOKEN" ]; then
  printf 'WARNING: SONAR_TOKEN is empty. A local SonarQube with default settings may still accept the scan, but CI needs a token.\n' >&2
fi

printf 'SonarQube: %s\n' "$SONAR_URL"
failures=0
for i in "${!modules[@]}"; do
  module="${modules[$i]}"
  # Keep historical Sonar project keys stable after the services/ move.
  key_module="${module#services/}"
  key="$PREFIX-${key_module//\//-}"
  printf '\n=== analysing %s ===\n' "$module"
  args=(
    -B -ntp "$PLUGIN"
    "-Dsonar.host.url=$SONAR_URL"
    "-Dsonar.projectKey=$key"
    "-Dsonar.projectName=$key"
    "-Dsonar.projectVersion=${SONAR_PROJECT_VERSION:-1.0}"
    "-Dsonar.sources=."
    "-Dsonar.exclusions=**/target/**,**/node_modules/**"
  )
  [ -n "$SONAR_TOKEN" ] && args+=("-Dsonar.token=$SONAR_TOKEN")
  ( cd "$ROOT/$module" && "${runners[$i]}" "${args[@]}" ) || failures=$((failures + 1))
done

if [ "$failures" -gt 0 ]; then
  printf '\n%d module(s) failed the SonarQube scan.\n' "$failures" >&2
  exit 1
fi
printf '\nAll modules analysed. Open %s to see the results.\n' "$SONAR_URL"

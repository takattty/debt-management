#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/toolchain-env.sh
mkdir -p work
export npm_config_cache="${npm_config_cache:-$PWD/work/npm-cache}"
export MAVEN_USER_HOME="${MAVEN_USER_HOME:-$PWD/work/maven-home}"
export DB_URL="${DB_URL:-jdbc:postgresql://localhost:5432/debt}"
export DB_USERNAME="${DB_USERNAME:-debt}"
export DB_PASSWORD="${DB_PASSWORD:-debt_local}"
export DEV_PASSWORD="${DEV_PASSWORD:-debt_local}"
export SPRING_PROFILES_ACTIVE=dev
maven_args=(-B -ntp "-Dmaven.repo.local=${MAVEN_REPO_LOCAL:-$PWD/work/m2}")
if [ -n "${MAVEN_SETTINGS:-}" ]; then maven_args+=(-s "$MAVEN_SETTINGS"); fi
backend/mvnw "${maven_args[@]}" -f backend/pom.xml verify
npm --prefix frontend ci
npm --prefix frontend run lint
npm --prefix frontend run typecheck
npm --prefix frontend run test:unit
npm --prefix frontend run build
java -jar backend/target/debt-management-0.1.0-SNAPSHOT.jar >work/backend.log 2>&1 &
backend_pid=$!
trap 'kill "$backend_pid" 2>/dev/null || true' EXIT
ready=false
for _ in $(seq 1 60); do
  if curl --silent --fail -u "alpha-admin:$DEV_PASSWORD" http://localhost:8080/api/session >/dev/null; then ready=true; break; fi
  if ! kill -0 "$backend_pid" 2>/dev/null; then cat work/backend.log; exit 1; fi
  sleep 1
done
if [ "$ready" != true ]; then cat work/backend.log; exit 1; fi
npm --prefix frontend run test:e2e

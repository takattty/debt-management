#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/toolchain-env.sh
mkdir -p work
if [ -n "${E2E_BASE_URL:-}" ]; then
  echo 'Unset E2E_BASE_URL: verify must test the frontend/backend started from this commit.' >&2
  exit 1
fi
if [ -n "${API_PROXY_TARGET:-}" ] && [ "$API_PROXY_TARGET" != 'http://127.0.0.1:8080' ]; then
  echo 'Unset API_PROXY_TARGET: verify must use its own backend on 127.0.0.1:8080.' >&2
  exit 1
fi
export API_PROXY_TARGET=http://127.0.0.1:8080
export npm_config_cache="${npm_config_cache:-$PWD/work/npm-cache}"
python3 scripts/failure-loop.py check
python3 -m unittest discover -s tests
npm --prefix .github/scripts ci --ignore-scripts
npm --prefix .github/scripts audit --audit-level=high
node --test .github/scripts/*.test.cjs
python3 scripts/assert-ports-free.py 8080 5173
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PWD/work/gradle-home}"
export DB_URL="${DB_URL:-jdbc:postgresql://localhost:5432/debt}"
export DB_USERNAME="${DB_USERNAME:-debt}"
export DB_PASSWORD="${DB_PASSWORD:-debt_local}"
export LOCAL_SEED_PASSWORD="${LOCAL_SEED_PASSWORD:-debt_local_demo_2026}"
export SPRING_PROFILES_ACTIVE=local
(cd backend && ./gradlew --no-daemon check bootJar)
npm --prefix frontend ci
npm --prefix frontend audit --audit-level=high
npm --prefix frontend run check:api
npm --prefix frontend run test:api-drift
npm --prefix frontend run lint
npm --prefix frontend run typecheck
npm --prefix frontend run test:unit
npm --prefix frontend run build
java -jar backend/build/libs/debt-management.jar >work/backend.log 2>&1 &
backend_pid=$!
trap 'kill "$backend_pid" 2>/dev/null || true' EXIT
ready=false
for _ in $(seq 1 60); do
  if ! kill -0 "$backend_pid" 2>/dev/null; then cat work/backend.log; exit 1; fi
  if grep -q "Started DebtApplication" work/backend.log && curl --silent --fail http://localhost:8080/api/csrf >/dev/null; then
    kill -0 "$backend_pid" || { cat work/backend.log; exit 1; }
    ready=true
    break
  fi
  if ! kill -0 "$backend_pid" 2>/dev/null; then cat work/backend.log; exit 1; fi
  sleep 1
done
if [ "$ready" != true ]; then cat work/backend.log; exit 1; fi
RUNN_BASE_URL=http://127.0.0.1:8080 scripts/runn.sh run --scopes read:parent backend/runn/*.yml
npm --prefix frontend run test:e2e
kill -0 "$backend_pid" || { cat work/backend.log; exit 1; }

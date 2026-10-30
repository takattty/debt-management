#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/check-workspace-capacity.py
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
python3 scripts/check-workspace-capacity.py
(cd backend && ./gradlew --no-daemon check bootJar)
python3 scripts/check-workspace-capacity.py
npm --prefix frontend ci
npm --prefix frontend audit --audit-level=high
npm --prefix frontend run check:api
npm --prefix frontend run test:api-drift
npm --prefix frontend run lint
npm --prefix frontend run typecheck
npm --prefix frontend run test:unit
npm --prefix frontend run build
# Runtime lifecycle (also exercised with a real artifact by the phase acceptance harness).
backend_pid=''
check_backend_artifact() {
  if [ "$(sha256sum "$backend_artifact" | cut -d ' ' -f1)" != "$backend_fingerprint" ]; then
    echo 'Backend artifact changed between verification phases.' >&2
    return 1
  fi
}
terminate_backend() {
  [ -n "$backend_pid" ] || return 0
  kill "$backend_pid" 2>/dev/null || true
  for _ in $(seq 1 30); do
    if ! kill -0 "$backend_pid" 2>/dev/null; then break; fi
    sleep 1
  done
  if kill -0 "$backend_pid" 2>/dev/null; then
    kill -KILL "$backend_pid" 2>/dev/null || true
    wait "$backend_pid" 2>/dev/null || true
    backend_pid=''
    echo 'Backend did not stop gracefully.' >&2
    return 1
  fi
  local result=0
  wait "$backend_pid" || result=$?
  backend_pid=''
  [ "$result" -eq 0 ] || [ "$result" -eq 143 ]
}
cleanup_backend() {
  local result="$1"
  trap - EXIT
  terminate_backend || true
  exit "$result"
}
start_backend() {
  python3 scripts/check-workspace-capacity.py
  python3 scripts/assert-ports-free.py "$backend_port"
  check_backend_artifact
  backend_log="work/backend-$1.log"
  java -jar "$backend_artifact" --server.port="$backend_port" >"$backend_log" 2>&1 &
  backend_pid=$!
  for _ in $(seq 1 60); do
    if ! kill -0 "$backend_pid" 2>/dev/null; then break; fi
    if grep -q 'Started DebtApplication' "$backend_log" && curl --silent --fail "http://127.0.0.1:$backend_port/api/csrf" >/dev/null; then
      kill -0 "$backend_pid"
      return
    fi
    sleep 1
  done
  echo "Backend did not become ready; see $backend_log." >&2
  return 1
}
stop_backend() {
  if ! kill -0 "$backend_pid" 2>/dev/null; then
    echo "Backend exited before its verification phase completed; see $backend_log." >&2
    return 1
  fi
  terminate_backend
  python3 scripts/assert-ports-free.py "$backend_port"
}

# Run API and browser phases against the same artifact and unchanged database configuration.
backend_artifact=backend/build/libs/debt-management.jar
backend_fingerprint="$(sha256sum "$backend_artifact" | cut -d ' ' -f1)"
backend_port=8080
trap 'cleanup_backend "$?"' EXIT
start_backend api
RUNN_BASE_URL=http://127.0.0.1:8080 scripts/runn.sh run --scopes read:parent backend/runn/*.yml
bash scripts/verify-proxy.sh
stop_backend
# Login counters are process-local. Preserve facts while giving browser tests their own phase.
start_backend e2e
npm --prefix frontend run test:e2e
check_backend_artifact
stop_backend

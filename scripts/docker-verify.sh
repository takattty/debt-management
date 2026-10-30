#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
docker_command=(docker)
cloud_local=false
staged=false
prepare_only=false
if [[ "${1:-}" == '--cloud-local' ]]; then
  cloud_local=true
  docker_command=(bash "$PWD/scripts/docker-cloud.sh")
  shift
fi
if [[ "${1:-}" == '--staged' ]] && "$cloud_local"; then
  staged=true
  shift
fi
if [[ "${1:-}" == '--prepare-only' ]] && "$staged"; then
  prepare_only=true
  shift
fi
[[ "$#" -eq 0 ]] || { echo 'Usage: scripts/docker-verify.sh [--cloud-local [--staged [--prepare-only]]]' >&2; exit 2; }
if "$staged"; then command -v timeout >/dev/null; fi
scripts/build-local.sh
export BUILDX_CONFIG="${BUILDX_CONFIG:-$PWD/work/buildx}"
web_port="${WEB_PORT:-8088}"
[[ "$web_port" =~ ^[0-9]+$ ]] && (( 10#$web_port > 0 && 10#$web_port < 65536 )) || { echo "Invalid WEB_PORT" >&2; exit 1; }
capacity_for() {
  python3 scripts/check-docker-capacity.py --cloud-local --service "$2" "$1"
}
wait_for_service() {
  local target="$1" field="$2" deadline=$((SECONDS + 120)) remaining report ready_status
  while (( SECONDS < deadline )); do
    remaining=$((deadline - SECONDS))
    (( remaining > 0 )) || break
    report=$(timeout "$remaining" python3 scripts/check-docker-capacity.py --cloud-local --service "$target" up) || return "$?"
    printf '%s\n' "$report"
    if printf '%s\n' "$report" | python3 -c '
import json, sys
try:
    report = json.load(sys.stdin)
    services = report["services"]
    if len(services) != 1 or services[0]["service"] != sys.argv[1] or report["sufficient"] is not True:
        raise ValueError("Unexpected readiness scope")
    current, ready = services[0]["current_container"], services[0][sys.argv[2]]
    if type(current) is not bool or type(ready) is not bool:
        raise ValueError("Missing readiness state")
except (ValueError, KeyError, TypeError):
    sys.exit(2)
sys.exit(0 if current and ready else 1)
' "$target" "$field"; then
      return 0
    else
      ready_status=$?
      [[ "$ready_status" -eq 1 ]] || return "$ready_status"
    fi
    sleep 1
  done
  echo "Compose service readiness timed out: $target ($field)" >&2
  return 1
}
up_service() {
  capacity_for up "$1"
  "${docker_command[@]}" compose up --no-build --no-deps -d "$1"
}
if "$staged"; then
  capacity_for build backend
  "${docker_command[@]}" compose build backend
  capacity_for build frontend
  "${docker_command[@]}" compose build frontend
  up_service postgres
  wait_for_service postgres healthy
  up_service backend
  wait_for_service backend running
  up_service frontend
  wait_for_service frontend running
  wait_for_service postgres healthy
elif "$cloud_local"; then
  python3 scripts/check-docker-capacity.py --cloud-local build
  "${docker_command[@]}" compose build
  python3 scripts/check-docker-capacity.py --cloud-local up
  "${docker_command[@]}" compose up --no-build -d
else
  "${docker_command[@]}" compose up --build -d
fi
for _ in $(seq 1 60); do
  if curl --silent --fail http://127.0.0.1:$web_port/ >/dev/null && curl --silent --fail http://127.0.0.1:$web_port/api/csrf >/dev/null; then
    if "$prepare_only"; then
      echo 'Compose preparation complete. API/proxy/browser acceptance has not been run by this command; this is not a quality PASS.'
      exit 0
    fi
    E2E_BASE_URL=http://127.0.0.1:$web_port npm --prefix frontend run test:e2e
    exit 0
  fi
  sleep 1
done
"${docker_command[@]}" compose logs
exit 1

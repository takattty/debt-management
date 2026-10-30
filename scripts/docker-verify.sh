#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
docker_command=(docker)
cloud_local=false
if [[ "${1:-}" == '--cloud-local' ]]; then
  cloud_local=true
  docker_command=(bash "$PWD/scripts/docker-cloud.sh")
  shift
fi
[[ "$#" -eq 0 ]] || { echo 'Usage: scripts/docker-verify.sh [--cloud-local]' >&2; exit 2; }
scripts/build-local.sh
export BUILDX_CONFIG="${BUILDX_CONFIG:-$PWD/work/buildx}"
web_port="${WEB_PORT:-8088}"
[[ "$web_port" =~ ^[0-9]+$ ]] && (( 10#$web_port > 0 && 10#$web_port < 65536 )) || { echo "Invalid WEB_PORT" >&2; exit 1; }
if "$cloud_local"; then
  python3 scripts/check-docker-capacity.py --cloud-local build
  "${docker_command[@]}" compose build
  python3 scripts/check-docker-capacity.py --cloud-local up
  "${docker_command[@]}" compose up --no-build -d
else
  "${docker_command[@]}" compose up --build -d
fi
for _ in $(seq 1 60); do
  if curl --silent --fail http://127.0.0.1:$web_port/ >/dev/null && curl --silent --fail http://127.0.0.1:$web_port/api/csrf >/dev/null; then
    E2E_BASE_URL=http://127.0.0.1:$web_port npm --prefix frontend run test:e2e
    exit 0
  fi
  sleep 1
done
"${docker_command[@]}" compose logs
exit 1

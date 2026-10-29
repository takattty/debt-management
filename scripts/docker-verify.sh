#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
scripts/build-local.sh
export BUILDX_CONFIG="${BUILDX_CONFIG:-$PWD/work/buildx}"
web_port="${WEB_PORT:-8088}"
[[ "$web_port" =~ ^[0-9]+$ ]] && (( 10#$web_port > 0 && 10#$web_port < 65536 )) || { echo "Invalid WEB_PORT" >&2; exit 1; }
docker compose up --build -d
for _ in $(seq 1 60); do
  if curl --silent --fail http://127.0.0.1:$web_port/ >/dev/null && curl --silent --fail http://127.0.0.1:$web_port/api/csrf >/dev/null; then
    E2E_BASE_URL=http://127.0.0.1:$web_port npm --prefix frontend run test:e2e
    exit 0
  fi
  sleep 1
done
docker compose logs
exit 1

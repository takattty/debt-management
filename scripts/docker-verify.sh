#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
scripts/build-local.sh
export BUILDX_CONFIG="${BUILDX_CONFIG:-$PWD/work/buildx}"
docker compose up --build -d
for _ in $(seq 1 60); do
  if curl --silent --fail http://127.0.0.1:8088/ >/dev/null && curl --silent --fail -u alpha-admin:debt_local http://127.0.0.1:8088/api/session >/dev/null; then
    E2E_BASE_URL=http://127.0.0.1:8088 npm --prefix frontend run test:e2e
    exit 0
  fi
  sleep 1
done
docker compose logs
exit 1

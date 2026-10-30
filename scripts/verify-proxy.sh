#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p work
proxy_port="${PROXY_TEST_PORT:-8096}"
[[ "$proxy_port" =~ ^[0-9]+$ ]] && (( 10#$proxy_port > 0 && 10#$proxy_port < 65536 )) || exit 1
python3 scripts/assert-ports-free.py "$proxy_port"
python3 scripts/prepare-proxy-fixtures.py
docker_local() {
  env -u DOCKER_HOST -u DOCKER_CONTEXT -u DOCKER_TLS -u DOCKER_TLS_VERIFY -u DOCKER_CERT_PATH \
    docker --host=unix:///var/run/docker.sock "$@"
}
docker_local info >/dev/null
proxy_image="$(awk '$1 == "FROM" { print $2; exit }' frontend/Dockerfile)"
proxy_container_id=''
cleanup() {
  if [[ "$proxy_container_id" =~ ^[0-9a-f]{64}$ ]]; then docker_local rm --force "$proxy_container_id" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT
proxy_container_id="$(docker_local run --detach --rm --add-host backend:host-gateway \
  --publish "127.0.0.1:$proxy_port:8080" \
  --mount "type=bind,src=$PWD/frontend/nginx.conf,dst=/etc/nginx/conf.d/default.conf,readonly" "$proxy_image")"
proxy_ready=false
for _ in $(seq 1 30); do
  if curl --silent --fail "http://127.0.0.1:$proxy_port/api/csrf" >/dev/null; then proxy_ready=true; break; fi
  sleep 1
done
if [ "$proxy_ready" != true ]; then docker_local logs "$proxy_container_id"; exit 1; fi
RUNN_BASE_URL="http://127.0.0.1:$proxy_port" scripts/runn.sh run --scopes read:parent backend/runn/proxy/uploads.yml

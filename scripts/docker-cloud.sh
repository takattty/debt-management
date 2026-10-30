#!/usr/bin/env bash
# Explicit managed-Cloud local daemon; leave registry/proxy configuration untouched.
set -euo pipefail
exec env -u DOCKER_HOST -u DOCKER_CONTEXT -u DOCKER_TLS -u DOCKER_TLS_VERIFY -u DOCKER_CERT_PATH \
  docker --host=unix:///var/run/docker.sock "$@"

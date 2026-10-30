#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p work
echo 'Verification log: work/cloud-verify.log'
exec scripts/verify.sh >work/cloud-verify.log 2>&1

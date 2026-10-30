#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/check-workspace-capacity.py
mkdir -p work
echo 'Verification log: work/cloud-verify.log'
exec scripts/verify.sh >work/cloud-verify.log 2>&1

#!/usr/bin/env bash
set -euo pipefail
runn_script_dir="$(cd "$(dirname "$0")" && pwd)"
runn_binary="$(python3 "$runn_script_dir/install-runn.py")"
if [ "${1:-}" = run ]; then
  python3 "$runn_script_dir/prepare-runn-fixtures.py"
fi
exec "$runn_binary" "$@"

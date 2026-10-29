#!/usr/bin/env bash
set -euo pipefail
runn_script_dir="$(cd "$(dirname "$0")" && pwd)"
runn_binary="$(python3 "$runn_script_dir/install-runn.py")"
exec "$runn_binary" "$@"

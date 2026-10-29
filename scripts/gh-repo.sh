#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -lt 2 ] || [[ ! "$1" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]; then
  echo 'Usage: gh-repo.sh owner/repository command [arguments...]' >&2
  exit 2
fi
selected_repository="$1"
shift
exec env GH_REPO="$selected_repository" gh "$@"

#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -lt 2 ] || [[ ! "$1" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]; then
  echo 'Usage: gh-repo.sh owner/repository command [arguments...]' >&2
  exit 2
fi
selected_repository="$1"
shift
# gh pr view exposes a limited JSON field list, not every GraphQL field.
# Reject this observed mistake before contacting GitHub and point to the REST contract.
if [ "${1:-}" = pr ] && [ "${2:-}" = view ]; then
  json_next=false
  for argument in "$@"; do
    json_fields=''
    if [ "$json_next" = true ]; then
      json_fields="$argument"
      json_next=false
    elif [ "$argument" = --json ]; then
      json_next=true
    elif [[ "$argument" = --json=* ]]; then
      json_fields="${argument#--json=}"
    fi
    if [[ ",$json_fields," = *,baseRefOid,* ]]; then
      echo 'gh pr view does not support baseRefOid. Use python3 /absolute/checkout/scripts/gh-pr-metadata.py owner/repository PR_NUMBER for REST head/base metadata.' >&2
      exit 2
    fi
  done
fi
exec env GH_REPO="$selected_repository" gh "$@"

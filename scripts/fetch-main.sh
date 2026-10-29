#!/usr/bin/env bash
set -euo pipefail
# Explicit destination matters when remote.origin.fetch is absent or restricted.
git fetch origin refs/heads/main:refs/remotes/origin/main
fetched_main_sha="$(git rev-parse refs/remotes/origin/main)"
remote_main_sha="$(git ls-remote --exit-code origin refs/heads/main | cut -f1)"
if [ "$fetched_main_sha" != "$remote_main_sha" ]; then
  echo 'Remote main moved or tracking ref did not update; do not use the stale base.' >&2
  exit 1
fi
printf '%s\n' "$fetched_main_sha"

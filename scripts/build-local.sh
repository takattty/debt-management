#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/check-workspace-capacity.py
source scripts/toolchain-env.sh
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PWD/work/gradle-home}"
export npm_config_cache="${npm_config_cache:-$PWD/work/npm-cache}"
(cd backend && ./gradlew --no-daemon bootJar)
python3 scripts/check-workspace-capacity.py
npm --prefix frontend ci
python3 scripts/check-workspace-capacity.py
npm --prefix frontend run build

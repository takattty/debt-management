#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/toolchain-env.sh
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PWD/work/gradle-home}"
export npm_config_cache="${npm_config_cache:-$PWD/work/npm-cache}"
(cd backend && ./gradlew --no-daemon bootJar)
npm --prefix frontend ci
npm --prefix frontend run build

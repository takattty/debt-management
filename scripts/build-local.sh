#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/toolchain-env.sh
export MAVEN_USER_HOME="${MAVEN_USER_HOME:-$PWD/work/maven-home}"
export npm_config_cache="${npm_config_cache:-$PWD/work/npm-cache}"
args=(-B -ntp "-Dmaven.repo.local=${MAVEN_REPO_LOCAL:-$PWD/work/m2}")
if [ -n "${MAVEN_SETTINGS:-}" ]; then args+=(-s "$MAVEN_SETTINGS"); fi
backend/mvnw "${args[@]}" -f backend/pom.xml package -DskipTests
npm --prefix frontend ci
npm --prefix frontend run build

#!/usr/bin/env bash
# Source after installing Java25. Session-specific files stay outside the repo.
if [ -d /workspace/toolchains/java25 ]; then
  export JAVA_HOME=/workspace/toolchains/java25
  export PATH="$JAVA_HOME/bin:$PATH"
fi
if [ -d /workspace/toolchains/gradle-home ]; then
  export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/workspace/toolchains/gradle-home}"
fi

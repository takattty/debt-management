#!/usr/bin/env bash
# Source after installing Java 25 and Maven 3.9.11. No secret discovery.
if [ -d /workspace/toolchains/java25 ]; then
  export JAVA_HOME=/workspace/toolchains/java25
  export PATH="$JAVA_HOME/bin:$PATH"
fi
if [ -d /workspace/toolchains/apache-maven-3.9.11 ]; then
  export PATH="/workspace/toolchains/apache-maven-3.9.11/bin:$PATH"
fi
if [ -f /workspace/toolchains/maven-settings.xml ]; then
  export MAVEN_SETTINGS="${MAVEN_SETTINGS:-/workspace/toolchains/maven-settings.xml}"
fi
if [ -f /workspace/toolchains/java-cacerts ]; then
  export MAVEN_OPTS="${MAVEN_OPTS:-} -Djavax.net.ssl.trustStore=/workspace/toolchains/java-cacerts -Djavax.net.ssl.trustStorePassword=changeit"
fi
if [ -d /workspace/toolchains/m2 ]; then
  export MAVEN_REPO_LOCAL="${MAVEN_REPO_LOCAL:-/workspace/toolchains/m2}"
fi

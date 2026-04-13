#!/bin/sh
# Maven Wrapper script - downloads Maven if not present
# Based on https://github.com/apache/maven-wrapper

set -e

MAVEN_PROJECTBASEDIR="${MAVEN_BASEDIR:-$(cd "$(dirname "$0")" && pwd)}"
MAVEN_WRAPPER_JAR="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.jar"
MAVEN_WRAPPER_PROPERTIES="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.properties"

if [ -f "$MAVEN_WRAPPER_PROPERTIES" ]; then
    MAVEN_URL=$(grep 'distributionUrl' "$MAVEN_WRAPPER_PROPERTIES" | cut -d'=' -f2-)
fi

MAVEN_HOME="$HOME/.m2/wrapper/dists/apache-maven-3.9.9"

if [ ! -d "$MAVEN_HOME" ]; then
    echo "Downloading Maven..."
    mkdir -p "$MAVEN_HOME"
    curl -fsSL "$MAVEN_URL" -o "/tmp/maven.zip"
    unzip -q "/tmp/maven.zip" -d "$HOME/.m2/wrapper/dists/"
    rm "/tmp/maven.zip"
fi

exec "$MAVEN_HOME/bin/mvn" "$@"

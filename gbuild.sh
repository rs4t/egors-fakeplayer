#!/bin/sh
# Build in Docker (no local JDK 25 needed). Output: build/libs/
cd "$(dirname "$0")" && exec docker run --rm -u "$(id -u):$(id -g)" -e GRADLE_USER_HOME=/w/.gradle -v "$PWD":/w -w /w gradle:jdk25 ./gradlew --no-daemon -q build "$@"

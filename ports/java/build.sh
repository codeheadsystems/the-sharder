#!/bin/sh
# Build the Java port: compile, the unit tests, and the conformance suite at the levels the port
# reaches.  The Gradle wrapper pins the distribution, so the build needs a JDK of 21 or above and
# nothing else installed.
#
#   ./build.sh              compile, test, and assemble
#   ./build.sh <task> ...   run other Gradle tasks instead

set -e

cd "$(dirname "$0")"

if [ "$#" -eq 0 ]; then
    exec ./gradlew build
fi

exec ./gradlew "$@"

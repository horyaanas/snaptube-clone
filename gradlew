#!/bin/sh
# Resilient Gradle launcher
if command -v gradle >/dev/null 2>&1; then
    exec gradle "$@"
elif [ -f "$(dirname "$0")/gradle/wrapper/gradle-wrapper.jar" ]; then
    exec java -jar "$(dirname "$0")/gradle/wrapper/gradle-wrapper.jar" "$@"
else
    echo "Gradle executable not found on PATH"
    exit 1
fi

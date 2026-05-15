#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEMO_JAR="$SCRIPT_DIR/demo/build/libs/demo.jar"

if [ ! -f "$DEMO_JAR" ]; then
    echo "demo.jar not found. Building..."
    (cd "$SCRIPT_DIR" && ./gradlew jar)
    (cd "$SCRIPT_DIR/demo" && ./gradlew shadowJar)
fi

echo
echo "Clogger Demo output below:"
echo
java -jar "$DEMO_JAR"
echo
echo "Demo finished."

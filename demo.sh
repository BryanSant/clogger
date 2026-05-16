#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEMO_JAR="$SCRIPT_DIR/demo/build/libs/demo.jar"
CLOGGER_JAR=$(ls "$SCRIPT_DIR"/build/libs/clogger*.jar 2>/dev/null | head -n 1)

needs_build=false
if [ ! -f "$DEMO_JAR" ]; then
    echo "demo.jar not found. Building..."
    needs_build=true
elif [ -n "$CLOGGER_JAR" ] && [ "$CLOGGER_JAR" -nt "$DEMO_JAR" ]; then
    echo "demo.jar is older than $(basename "$CLOGGER_JAR"). Rebuilding..."
    needs_build=true
fi

if [ "$needs_build" = true ]; then
    (cd "$SCRIPT_DIR" && ./gradlew jar)
    (cd "$SCRIPT_DIR/demo" && ./gradlew shadowJar)
fi

echo
echo "Clogger Demo output below:"
echo
java -jar "$DEMO_JAR"
echo
echo "Demo finished."

#!/usr/bin/env sh
# ============================================
#  task-scheduler startup script (Linux/macOS)
#  Uses the Java runtime under scheduler/jdk
# ============================================

BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAVA_EXE="$BASE_DIR/jdk/bin/java"
JAR_PATH="$BASE_DIR/server/task-scheduler.jar"

if [ ! -x "$JAVA_EXE" ]; then
    echo "[ERROR] Java runtime not found: $JAVA_EXE"
    echo "Please put a JDK/JRE into the 'jdk' folder next to this script."
    exit 1
fi

if [ ! -f "$JAR_PATH" ]; then
    echo "[ERROR] Application jar not found: $JAR_PATH"
    echo "Please run 'mvn package' first to generate the scheduler folder."
    exit 1
fi

echo "Using Java: $JAVA_EXE"
exec "$JAVA_EXE" $JAVA_OPTS -jar "$JAR_PATH" --scheduler.task-config-location=file:"$BASE_DIR/conf/scheduler/tasks.yaml" "$@"

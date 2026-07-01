#!/usr/bin/env bash
# Generic dispatcher: forwards CLI args to Main.
# Runs from the results/ directory so that result_*.txt and Summary_Report_*.txt
# are grouped there instead of scattered across the project root.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SPM_HOME="$(cd "$SCRIPT_DIR/.." && pwd)"
JAR="$SPM_HOME/target/adaptive-itr-spm.jar"

if [ ! -f "$JAR" ]; then
    echo "[run] Fat jar not found at $JAR"
    echo "[run] Building now..."
    (cd "$SPM_HOME" && mvn -B -DskipTests=true package)
fi

mkdir -p "$SPM_HOME/results"
cd "$SPM_HOME/results"

SPM_HEAP="${SPM_HEAP:-16g}"
exec java -Xmx"$SPM_HEAP" -Dfile.encoding=UTF-8 -Dspm.home="$SPM_HOME" -jar "$JAR" "$@"

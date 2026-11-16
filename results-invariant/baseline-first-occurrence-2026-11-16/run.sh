#!/usr/bin/env bash
# Runs the baselines and the engine on three small databases that separate first-occurrence projection
# from full projection. Run from the repository root.
set -euo pipefail
O=results-invariant/baseline-first-occurrence-2026-11-16
JAR=bench/adaptive-itr-spm-bench.jar
mkdir -p $O/classes
javac -d $O/classes -cp $JAR tools/verify/java/BaselineFirstOccurrence.java
{
  echo "git $(git rev-parse --short HEAD); jar sha256 $(shasum -a 256 $JAR | cut -c1-16); BaselineFirstOccurrence sha256 $(shasum -a 256 tools/verify/java/BaselineFirstOccurrence.java | cut -c1-16)"
  java -version 2>&1 | head -1
} > $O/environment.txt
java -cp "$JAR:$O/classes" com.adaptive.itr.spm.BaselineFirstOccurrence | grep '^case' > $O/output.txt

#!/usr/bin/env bash
# Runs the benchmark engine on the six-sequence running example of the manuscript. Run from the repository root.
set -euo pipefail
O=results-invariant/running-example-2026-11-15
JAR=bench/adaptive-itr-spm-bench.jar
mkdir -p $O/classes
javac -d $O/classes -cp $JAR tools/verify/java/RunningExample.java
{
  echo "git $(git rev-parse --short HEAD); jar sha256 $(shasum -a 256 $JAR | cut -c1-16); RunningExample sha256 $(shasum -a 256 tools/verify/java/RunningExample.java | cut -c1-16)"
  java -version 2>&1 | head -1
} > $O/environment.txt
java -cp "$JAR:$O/classes" com.adaptive.itr.spm.RunningExample > $O/output.txt

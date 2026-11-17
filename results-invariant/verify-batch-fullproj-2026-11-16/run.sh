#!/usr/bin/env bash
# Stream verification of the batch baseline after the projection fix (every itemset where the
# prefix ends is kept): BatchTaSPM against RemineTaSPM after every batch (D_init + 100 increments)
# on the seven datasets of the experiments, with the committed benchmark jar. Run from the repo
# root. Each run is one JVM; four run at a time. Correctness only: times in these files are void.
set -u
OUT=results-invariant/verify-batch-fullproj-2026-11-16
JAR=bench/adaptive-itr-spm-bench.jar
{
  echo "git $(git rev-parse HEAD) tree $(git status --porcelain -- src bench | wc -l | tr -d ' ') modified"
  shasum -a 256 $JAR
  java -version 2>&1 | head -1
  uname -sm
} > $OUT/environment.txt
for ds in bms1 D48K bible sign leviathan kosarak fifa; do echo "$ds BatchTaSPM"; done \
 | xargs -P 4 -L 1 sh -c 'java -Xms6g -Xmx6g -cp '"$JAR"' com.adaptive.itr.spm.StreamBench --config=stream_$0.txt --arm=$1 --mode=verify --timeout-min=240 --out='"$OUT"' > /dev/null 2>&1; echo "$0 $1 exit=$?"' | tee $OUT/exit_codes.txt

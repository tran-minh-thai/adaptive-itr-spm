#!/usr/bin/env bash
# Stream verification of the pattern-level pre-large baseline with its default buffer (irregular patterns dropped): PreLargeSeqTaSPM against RemineTaSPM
# after every batch (D_init + 100 increments) on the seven datasets of the experiments. Run from the
# repo root with the jar built by mvn package from the commit named in environment.txt (path given
# as the first argument). Each run is one JVM; three run at a time. Correctness only: times are void.
set -u
OUT=results-invariant/verify-prelarge-regprune-2026-11-17
JAR=$1
{
  echo "git $(git rev-parse HEAD) tree $(git status --porcelain -- src | wc -l | tr -d ' ') modified"
  echo "jar sha256 $(shasum -a 256 "$JAR" | cut -d' ' -f1) (mvn package of that commit)"
  java -version 2>&1 | head -1
  uname -sm
} > $OUT/environment.txt
for ds in bms1 D48K bible sign kosarak leviathan fifa; do echo "$ds PreLargeSeqTaSPM"; done \
 | xargs -P 3 -L 1 sh -c 'java -Xms8g -Xmx8g -cp '"$JAR"' com.adaptive.itr.spm.StreamBench --config=stream_$0.txt --arm=$1 --mode=verify --timeout-min=240 --out='"$OUT"' > /dev/null 2>&1; echo "$0 $1 exit=$?"' | tee $OUT/exit_codes.txt

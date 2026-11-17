#!/usr/bin/env bash
# PROBE, not a measurement: development machine, one repetition, no warm-up, 30-minute limit per
# stream. Purpose: decide whether the pattern-level pre-large baseline (PreLargeSeqTaSPM) can run
# within the 120-minute limit of the measurement machine, with its buffer kept by support only
# (as in Pre-HUSPM) and with irregular patterns dropped from the buffer. StaRSPM and Re-mine run
# in the same session for scale. Runs one at a time. Usage: run.sh <jar built at the commit in
# environment.txt>
set -u
OUT=results-probe/prelarge-probe-2026-11-17
JAR=$1
{
  echo "git $(git rev-parse HEAD) tree $(git status --porcelain -- src | wc -l | tr -d ' ') modified"
  echo "jar sha256 $(shasum -a 256 "$JAR" | cut -d' ' -f1) (mvn package of that commit)"
  java -version 2>&1 | head -1
  uname -sm
  echo "PROBE: development machine, reps=1, warmup=0, timeout 30 min, -Xmx16g"
} > $OUT/environment.txt
: > $OUT/exit_codes.txt
for ds in bms1 D48K leviathan; do
  for spec in "IncAdaTaSPM-StaticPreLarge -" "RemineTaSPM -" "PreLargeSeqTaSPM supportonly false" "PreLargeSeqTaSPM regprune true"; do
    set -- $spec
    arm=$1; tag=$2; rp=${3:-false}
    java -Xms16g -Xmx16g -Dprelarge.regprune=$rp -cp "$JAR" com.adaptive.itr.spm.StreamBench --config=stream_$ds.txt --arm=$arm --mode=time --tag=$tag --reps=1 --warmup=0 --timeout-min=30 --out=$OUT > /dev/null 2>&1
    echo "$ds $arm $tag exit=$?" | tee -a $OUT/exit_codes.txt
  done
done

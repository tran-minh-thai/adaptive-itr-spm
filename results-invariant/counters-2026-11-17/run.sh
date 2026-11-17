#!/usr/bin/env bash
# Deterministic counter run of the proposed method (IncAdaTaSPM-StaticPreLarge) on the seven
# datasets with the counted copy of the engine (tools/counters). The copy is generated from the
# measured source; for every batch the counters the measured engine also records are compared with
# the live run of the measurement machine (results/stream-bench/PC-THAI). Run from the repo root
# after mvn package. Counters do not depend on the machine; times are not recorded.
set -u
OUT=results-invariant/counters-2026-11-17
B=$(mktemp -d)
python3 tools/counters/make_counted_engine.py . "$B/src" > $OUT/generated_copy.txt
javac -nowarn -d "$B/classes" -cp target/classes "$B/src/IncAdaTaSPMCounted.java" tools/counters/CountersRun.java
{
  echo "git $(git rev-parse HEAD) tree $(git status --porcelain -- src tools | wc -l | tr -d ' ') modified"
  java -version 2>&1 | head -1
  uname -sm
} > $OUT/environment.txt
: > $OUT/summary.txt
for x in bms1:BMS1_spmf D48K:D48K-C10-T1-N5K bible:BIBLE kosarak:kosarak sign:SIGN leviathan:LEVIATHAN fifa:FIFA; do
  c=${x%%:*}; f=${x##*:}
  java -Xmx8g -cp "$B/classes:target/classes" com.adaptive.itr.spm.CountersRun stream_$c.txt $OUT/$f.csv results/stream-bench/PC-THAI/${f}__IncAdaTaSPM-StaticPreLarge__live.csv > "$B/log.txt"
  code=$?
  echo "$f exit=$code $(tail -1 "$B/log.txt")" | tee -a $OUT/summary.txt
done
rm -rf "$B"

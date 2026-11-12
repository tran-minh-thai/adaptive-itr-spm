#!/usr/bin/env bash
# Stream verification: every arm against RemineTaSPM after every batch (D_init + 100
# increments), on the eight datasets, with the committed benchmark jar. Run from the repo root.
# Each run is one JVM; four run at a time. Correctness only: times in these files are void.
set -u
OUT=results-invariant/verify-stream100-2026-11-12
JAR=bench/adaptive-itr-spm-bench.jar
for ds in kosarak leviathan sign bible fifa D45K D48K bms1; do
  for arm in IncAdaTaSPM IncAdaTaSPM-NoBuffer IncAdaTaSPM-StaticPreLarge IncAdaTaSPM-Original IncAdaTaSPM-TBR ItemPreLargeTaSPM BatchTaSPM; do
    echo "$ds $arm"
  done
done | xargs -P 4 -L 1 sh -c 'java -Xms6g -Xmx6g -cp '"$JAR"' com.adaptive.itr.spm.StreamBench --config=stream_$0.txt --arm=$1 --mode=verify --timeout-min=240 --out='"$OUT"' > /dev/null 2>&1; echo "$0 $1 exit=$?"'

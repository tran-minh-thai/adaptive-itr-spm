#!/usr/bin/env bash
# PROBE, phase 2 (same settings as run.sh). Phase 1 was stopped by hand after BMS1 StaRSPM, BMS1 Re-mine
# and 55 increments of BMS1 with the buffer by support only (files prefixed stopped-phase1__), when the
# author chose the buffer that drops irregular patterns as the baseline. Order: that buffer first on the
# three datasets, StaRSPM and Re-mine on D48K and LEVIATHAN, then the buffer by support only.
set -u
OUT=results-probe/prelarge-probe-2026-11-17
JAR=$1
run() {  # dataset arm tag regprune
  java -Xms16g -Xmx16g -Dprelarge.regprune=$4 -cp "$JAR" com.adaptive.itr.spm.StreamBench --config=stream_$1.txt --arm=$2 --mode=time --tag=$3 --reps=1 --warmup=0 --timeout-min=30 --out=$OUT > /dev/null 2>&1
  echo "$1 $2 $3 exit=$?" | tee -a $OUT/exit_codes.txt
}
for ds in bms1 D48K leviathan; do run $ds PreLargeSeqTaSPM regprune true; done
for ds in D48K leviathan; do run $ds IncAdaTaSPM-StaticPreLarge - false; run $ds RemineTaSPM - false; done
for ds in bms1 D48K leviathan; do run $ds PreLargeSeqTaSPM supportonly false; done

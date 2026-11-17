#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Fault injection for the stream verification of PreLargeSeqTaSPM (StreamBench --mode=verify).
# Each mutant is one textual replacement in a copy of PreLargeSeqTaSPM.java (it must occur exactly
# once); the copy is compiled against the jar and put before it on the class path, and the stream
# verification is run on the datasets listed for that mutant. The report gives, per run, the number of
# snapshots compared, the snapshots with a difference and the increments that rescanned, so that a
# mutant that is not caught can be told apart from a mutant that did not take effect.
#
# Usage (from the repository root): tools/verify/prelarge_stream_mutants.sh <jar> <output directory>
set -u
JAR=$1
OUT=$2
SRC=src/main/java/com/adaptive/itr/spm/PreLargeSeqTaSPM.java
mkdir -p "$OUT"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# name | replaced text | new text | datasets
MUTANTS=(
  "support-not-updated|sup[c] += l.size();|sup[c] += 0;|bms1"
  "swumax-zero|long q = swuMax(all, from);|long q = 0;|bms1 D48K bible"
)

for spec in "${MUTANTS[@]}"; do
  IFS='|' read -r name old new datasets <<< "$spec"
  mkdir -p "$WORK/$name/src" "$WORK/$name/classes"
  python3 - "$SRC" "$WORK/$name/src/PreLargeSeqTaSPM.java" "$old" "$new" <<'EOF' || exit 1
import sys
src = open(sys.argv[1], encoding='utf-8').read()
k = src.count(sys.argv[3])
if k != 1:
    sys.exit('replacement occurs %d times' % k)
open(sys.argv[2], 'w', encoding='utf-8').write(src.replace(sys.argv[3], sys.argv[4]))
EOF
  javac -nowarn -d "$WORK/$name/classes" -cp "$JAR" "$WORK/$name/src/PreLargeSeqTaSPM.java" || exit 1
  for ds in $datasets; do
    d="$OUT/$name"
    mkdir -p "$d"
    java -Xms8g -Xmx8g -cp "$WORK/$name/classes:$JAR" com.adaptive.itr.spm.StreamBench --config=stream_$ds.txt \
      --arm=PreLargeSeqTaSPM --mode=verify --timeout-min=60 --out="$d" > /dev/null 2>&1
    echo "$name $ds exit=$?"
  done
done | tee "$OUT/exit_codes.txt"

python3 - "$OUT" <<'EOF' | tee "$OUT/summary.txt"
import csv, glob, os, sys
out = sys.argv[1]
print('mutant dataset snapshots_compared snapshots_with_difference rescan_increments')
for f in sorted(glob.glob(os.path.join(out, '*', '*__verify.csv'))):
    rows = list(csv.DictReader(open(f)))
    bad = sum(r['status'] != 'SAME' for r in rows)
    resc = sum(int(r['rescans']) > 0 for r in rows[1:])
    print(os.path.basename(os.path.dirname(f)), os.path.basename(f).split('__')[0], len(rows), bad, resc)
EOF

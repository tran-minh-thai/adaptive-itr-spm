#!/bin/bash
# sum.sh <dataset>: one line per algorithm (batch-1 time, cumulative time, peak RAM) and the set comparison
d=$1; cd "$(dirname "$0")/$d"
a=$(grep "Batch 1/\|Batch 10/" log_AdaIncTaSPM.txt 2>/dev/null | sed 's/.*Time: \([0-9.]*\) ms | Cumulative: \([0-9.]*\).*/\1 \2/' | tr '\n' ' ')
b=$(grep "Batch 1/\|Batch 10/" log_IncAdaTaSPM.txt 2>/dev/null | sed 's/.* time=\([0-9.]*\) ms cum=\([0-9.]*\).*/\1 \2/' | tr '\n' ' ')
echo "$d RM[b1 . b10 cum]=$a | INC=$b | $(python3 ../cmpres.py result_AdaIncTaSPM.txt result_IncAdaTaSPM.txt 2>/dev/null)"

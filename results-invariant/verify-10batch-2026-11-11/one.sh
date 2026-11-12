#!/bin/bash
H="$1"; D="$2"; A="$3"; cd "$H/results/_verify_inc"; mkdir -p $D; cd $D
grep -v -i '^total_runs\|^warmup_runs' "$H/configs/config_$D.txt" > v.txt; printf 'total_runs=1\nwarmup_runs=0\n' >> v.txt
CP="$H/lib/verifyrun.jar:$H/lib/incada.jar:$H/target/classes"
s=$(date +%s.%N); timeout ${4:-165} java -Xmx${XMX:-2800m} -XX:+ExitOnOutOfMemoryError -Dspm.home="$H" -cp "$CP" com.adaptive.itr.spm.VerifyRun "$PWD/v.txt" $A > log_$A.txt 2>&1; rc=$?; e=$(date +%s.%N)
echo "$D $A rc=$rc wall=$(echo "$e - $s" | bc)"

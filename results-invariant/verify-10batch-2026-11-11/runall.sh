#!/bin/bash
H="$1"; cd "$H/results/_verify_inc"
CP="$H/lib/verifyrun.jar:$H/lib/incada.jar:$H/target/classes"
for D in sign bible fifa D45K D48K leviathan kosarak; do
  mkdir -p $D; cd $D
  grep -v -i '^total_runs\|^warmup_runs' "$H/configs/config_$D.txt" > v.txt; printf 'total_runs=1\nwarmup_runs=0\n' >> v.txt
  for A in AdaIncTaSPM IncAdaTaSPM; do
    s=$(date +%s.%N); timeout 3000 java -Xmx5g -Dspm.home="$H" -cp "$CP" com.adaptive.itr.spm.VerifyRun "$PWD/v.txt" $A > log_$A.txt 2>&1; e=$(date +%s.%N)
    echo "$D $A exit=$? wall=$(echo "$e - $s" | bc)" >> ../status.txt
  done
  cd ..
done
echo DONE >> status.txt

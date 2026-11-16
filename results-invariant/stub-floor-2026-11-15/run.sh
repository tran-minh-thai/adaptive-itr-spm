#!/usr/bin/env bash
# Checks the stub-floor statements (CheckStubFloor) on the committed benchmark jar: 2000 random
# cases with exact recount of every stub, one fault-injection run per statement, and the full
# streams (D_init + 100 increments) of the seven datasets for the proposed arm at phi 0.25, 0.5, 1.
# Correctness only; no timing. Run from the repository root.
set -uo pipefail
O=results-invariant/stub-floor-2026-11-15
JAR=bench/adaptive-itr-spm-bench.jar
mkdir -p $O/classes
javac -d $O/classes -cp $JAR tools/verify/java/CheckStubFloor.java
CP="$JAR:$O/classes"
{
  echo "git $(git rev-parse --short HEAD); jar sha256 $(shasum -a 256 $JAR | cut -c1-16); CheckStubFloor md5 $(md5 -q tools/verify/java/CheckStubFloor.java)"
  java -version 2>&1 | grep -v JAVA_TOOL_OPTIONS | head -1
} > $O/environment.txt
for m in none extra-rescan inflate-b miscount-stub stub-below-floor; do
  java -Xmx4g -cp "$CP" com.adaptive.itr.spm.CheckStubFloor random 2000 7 $m > $O/random_$m.txt 2>&1; echo "exit=$?" >> $O/random_$m.txt
done
for ds in sign leviathan fifa bible bms1 D48K kosarak; do
  for phi in 0.25 0.5 1; do echo "$ds $phi"; done
done | xargs -P 4 -L 1 sh -c 'java -Xmx12g -cp "'"$CP"'" com.adaptive.itr.spm.CheckStubFloor stream stream_$0.txt IncAdaTaSPM-StaticPreLarge none phi=$1 > '"$O"'/stream_$0_phi$1.txt 2>&1; echo "exit=$?" >> '"$O"'/stream_$0_phi$1.txt'
grep -h "RESULT\|^dataset" $O/*.txt > $O/summary.txt

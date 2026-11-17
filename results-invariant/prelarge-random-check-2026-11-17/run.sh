#!/usr/bin/env bash
# Exhaustive check of PreLargeSeqTaSPM on random small streams (both buffers) and fault injection of that
# check. Run from the repository root at the commit in environment.txt.
set -u
OUT=results-invariant/prelarge-random-check-2026-11-17
mvn -q -o compile || exit 1
CP=$(mktemp -d)
trap 'rm -rf "$CP"' EXIT
javac -nowarn -d "$CP" -cp target/classes tools/verify/java/BatchRandomCheck.java tools/verify/java/PreLargeRandomCheck.java || exit 1
{
  echo "git $(git rev-parse HEAD) tree $(git status --porcelain -- src tools/verify | wc -l | tr -d ' ') modified"
  java -version 2>&1 | head -1
  uname -sm
} > $OUT/environment.txt
for rp in true false; do
  java -cp "$CP:target/classes" com.adaptive.itr.spm.PreLargeRandomCheck 2000 20261117 0.5 $rp > $OUT/random_check_regprune_$rp.txt 2>&1
  echo "regularity_pruning=$rp exit=$?" >> $OUT/exit_codes.txt
done
python3 tools/verify/prelarge_mutants.py 2000 20261117 > $OUT/mutants.txt 2>&1
echo "mutants exit=$?" >> $OUT/exit_codes.txt

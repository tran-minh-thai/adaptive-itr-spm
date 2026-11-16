#!/usr/bin/env bash
# Checks the batch baseline after the projection fix. Run from the repository root.
#  1. BaselineFirstOccurrence (the three small databases that separated first-occurrence projection
#     from full projection) on the committed jar.
#  2. BatchRandomCheck: Batch, Re-mine and the engine against exhaustive enumeration on 2000 random
#     databases with multi-item itemsets, on the committed jar.
#  3. The same check on the jar of the main campaign (first-occurrence Batch), which must fail:
#     it shows the check detects the defect.
#  4. Class files of the two jars: which entries differ.
set -uo pipefail
O=results-invariant/batch-full-projection-2026-11-16
JAR=bench/adaptive-itr-spm-bench.jar
OLDREV=f370031
T=$(mktemp -d)
git show $OLDREV:bench/adaptive-itr-spm-bench.jar > $T/old.jar
{
  echo "git $(git rev-parse --short HEAD); jar sha256 $(shasum -a 256 $JAR | cut -c1-16); main-campaign jar ($OLDREV) sha256 $(shasum -a 256 $T/old.jar | cut -c1-16)"
  echo "BatchRandomCheck sha256 $(shasum -a 256 tools/verify/java/BatchRandomCheck.java | cut -c1-16); BaselineFirstOccurrence sha256 $(shasum -a 256 tools/verify/java/BaselineFirstOccurrence.java | cut -c1-16)"
  java -version 2>&1 | head -1
} > $O/environment.txt
mkdir -p $T/cn $T/co
javac -d $T/cn -cp $JAR tools/verify/java/BatchRandomCheck.java tools/verify/java/BaselineFirstOccurrence.java
javac -d $T/co -cp $T/old.jar tools/verify/java/BatchRandomCheck.java
java -cp "$JAR:$T/cn" com.adaptive.itr.spm.BaselineFirstOccurrence | grep '^case' > $O/first_occurrence_cases.txt
java -cp "$JAR:$T/cn" com.adaptive.itr.spm.BatchRandomCheck 2000 20261116 > $O/random_check.txt; echo "exit=$?" >> $O/random_check.txt
java -cp "$T/old.jar:$T/co" com.adaptive.itr.spm.BatchRandomCheck 2000 20261116 > $O/random_check_main_campaign_jar.txt; echo "exit=$?" >> $O/random_check_main_campaign_jar.txt
mkdir -p $T/o $T/n
(cd $T/o && unzip -q ../old.jar) && (cd $T/n && unzip -q "$OLDPWD/$JAR" 2>/dev/null || unzip -q "$(git -C "$OLDPWD" rev-parse --show-toplevel)/$JAR")
python3 - "$T/o" "$T/n" > $O/jar_class_diff.txt <<'PY'
import hashlib, os, sys
def h(d):
    r = {}
    for root, _, fs in os.walk(d):
        for f in fs:
            p = os.path.join(root, f)
            r[os.path.relpath(p, d).replace(os.sep, '/')] = hashlib.sha256(open(p, 'rb').read()).hexdigest()
    return r
o, n = h(sys.argv[1]), h(sys.argv[2])
co = [k for k in o if k.endswith('.class')]
print('class files: main-campaign jar %d, this jar %d, identical %d' % (len(co), len([k for k in n if k.endswith('.class')]), sum(1 for k in co if n.get(k) == o[k])))
for k in sorted(set(o) | set(n)):
    if o.get(k) != n.get(k):
        print('differs: %s%s' % (k, '' if k in o and k in n else ' (only in %s)' % ('this jar' if k in n else 'main-campaign jar')))
PY
rm -rf "$T"

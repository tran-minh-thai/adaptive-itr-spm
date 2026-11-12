#!/usr/bin/env bash
# Fault injection on the full stream (D_init + 100 increments): the no-rescan mutant on SIGN,
# where rescans do insert nodes (unlike BMS1, where the mutant could not act). Run from the
# repo root. Uses the committed benchmark jar plus VerifyMutants compiled against it.
set -euo pipefail
O=results-invariant/mutants-stream100-2026-11-12
JAR=bench/adaptive-itr-spm-bench.jar
{
  echo "command: $0; git $(git rev-parse --short HEAD); jar sha256 $(shasum -a 256 $JAR | cut -c1-16); VerifyMutants md5 $(md5 -q tools/verify/java/VerifyMutants.java)"
  java -version 2>&1 | head -1
  java -Xms6g -Xmx6g -cp "$JAR:$O/classes" com.adaptive.itr.spm.VerifyMutants stream_sign.txt no-rescan && echo "exit=0" || echo "exit=$?"
} 2>&1 | grep -v 'Picked up JAVA_TOOL_OPTIONS' > $O/SIGN_no-rescan.txt

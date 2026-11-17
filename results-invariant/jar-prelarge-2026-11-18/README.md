# Jar of phases prelarge and sigma against the jar of the main campaign (2026-11-18)

Old jar: `bench/adaptive-itr-spm-bench.jar` at commit `9fdb122` (sha256 `d0cf446b...`, built from
`7d22b60`), used by the main campaign and phase fix-batch. New jar: built by `tools/build_bench_jar.sh`
from a clean tree at commit `7e71346` (sha256 `8afa91f3...`). Command, from the repository root:

    git show 9fdb122:bench/adaptive-itr-spm-bench.jar > old.jar
    python3 results-invariant/jar-prelarge-2026-11-18/compare_jars.py old.jar bench/adaptive-itr-spm-bench.jar

Result (`comparison.txt`): of the 44 class files of the old jar, 30 have identical bytes and 43 identical
code once constant-pool indices, line-number tables, instruction offsets and the `ldc` / `ldc_w` choice are
masked (bytes differ in the other 13 because source lines moved, for example after license headers were
added). The new jar adds the four class files of PreLargeSeqTaSPM and its arm.

The one class with different code is `StreamBench`: two methods added (the PreLargeSeqTaSPM options), and
three changed, all in the arm registry (the arm list, `newArm`, `armParams`). Its method `stream`, which
holds the clock, has identical code; `load` and `sha256`, which run outside every clock, have the same
instructions up to offsets and one `ldc` that became `ldc_w` because the constant pool grew. So every arm
measured in the main campaign runs the code it ran there, and none is measured again.

# Ten-batch verification of IncAdaTaSPM v4 (2026-11-11)

Final-batch comparison of the pattern sets and supports of `IncAdaTaSPM` (v4) and the re-mine
class (then named `AdaIncTaSPM`, now `RemineTaSPM`) on the eight datasets, with the legacy
ten-batch split (`data_chunks = 0.1x10`). Kept as a record; it is superseded by the stream
verification (D_init plus 100 increments, every batch compared) in `../verify-stream100-*`.

* Not traceable to a commit: the runs used classes compiled with `javac` outside Maven from an
  uncommitted working tree. The source of the engine that ran is `IncAdaTaSPM_v4.java`
  (MD5 `9f59183f70dbd7365e19e8eb81e1b1e6`); `IncAdaTaSPM_v1.java` is the first version, which
  follows the design procedure without stubs or lazy insertion.
* `lib/`: the jars used by `one.sh` (`incada.jar` = v4, `incada_v1.jar` = v1, `verifyrun.jar` =
  a one-run launcher). `AlgorithmRegistry.*.bak` (not versioned) are the files replaced then.
* Per dataset: `v.txt` (configuration used, one run, no warm-up), `log_*.txt` (console output),
  `result_*.txt.gz` (patterns at the last batch, gzip). `cmpres.py` compares two result files;
  `sum.sh <dataset>` prints the comparison line. The paths inside the scripts are those of the
  layout at the time (`lib/`, `results/_verify_inc/`).
* Timings in the logs come from the development machine and are not measurements.
* Every `log_*.txt` began with a line printed by the JVM about a `JAVA_TOOL_OPTIONS` agent of the
  environment the runs used; that line (one per file, 16 in all) was removed before versioning.

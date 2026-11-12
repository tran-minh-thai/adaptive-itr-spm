# Equivalence checks for IncAdaTaSPM

- `prototype/`: Python prototype of the incremental procedure (`inc_proto.py`, with `remine`, a
  re-mine from scratch, as the reference) and its tests: `test_equiv.py` (pattern sets after
  every batch on small random data), `test_big.py` (larger cases, invariant I2 on every 20th),
  `mutants.py` (three injected faults that the comparison must catch), `test_mut.py`
  (non-emptiness of the outputs), `eta_effect.py` (rescans and tree size against eta).
- `java/IncEquivMulti.java`, `java/IncEquivMain.java`: run the Java engine on the case file
  written by `cmp_java_proto.py`. `-Dmutant=no-rescan|no-promotion` runs a deliberately broken
  engine (fault injection for the comparison).
- `java/VerifyMutants.java <stream config> [mutant names]`: fault injection for the stream
  verification of `StreamBench` on a real dataset (four broken engines and an unmodified control
  against `RemineTaSPM`), plus a check of the comparator on perturbed maps. It also prints how
  many rescans inserted a node: a mutant that removes rescans cannot be caught on a dataset
  where no rescan inserts one.
- `java/Bench.java`: runs the engine on one data file and prints, per batch, time, rescans,
  promotions, live nodes and tombstones. Development aid only; timings are not measurements.
  Usage: `Bench <data> <sigma> <maxPer> <targets> <eta> <lambda> <wV> <wR> <wS> [nb] [out]`.
- `cmp_java_proto.py <n> [base]`: compares the Java pattern sets with the prototype (and the
  prototype with `remine`) after every batch on n random cases; prints the number of cases
  whose pattern sets differ and whose rescan counts differ. The rescan counts agree only with
  `-Dinc.stubfrac=1 -Dinc.lazy=false`, which runs the procedure the prototype implements.
  Java classpath from `INC_CP`; extra JVM options from `INC_JOPTS`.
- `cmpres.py <re-mine result> <IncAdaTaSPM result>`: compares the final-batch pattern sets and
  supports of two legacy result files.

Compile the Java tools against the Maven classes:
`javac -d <dir> -cp target/classes tools/verify/java/*.java`.

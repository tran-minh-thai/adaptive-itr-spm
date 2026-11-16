# Stub-floor checks (2026-11-15)

`run.sh` checks the stub-floor statements with `tools/verify/java/CheckStubFloor.java`, a subclass
of the engine in the committed benchmark jar (the engine class is not modified). Correctness only.

* `random_none.txt`: 2000 random cases (phi in {0.25, 0.5, 0.75, 1}, eta in {0, 0.3, 0.5, 0.9},
  lazy insertion on and off, Score adaptive and constant); every stub recounted from the data.
* `random_<fault>.txt`: one injected fault per statement; each must be caught by its own check.
  `random_inflate-b.txt` was rerun once by hand (see the note in the file).
* `stream_<dataset>_phi<phi>.txt`: full stream (D_init and 100 increments), arm
  IncAdaTaSPM-StaticPreLarge, without stub recount (too costly on the full data).

A run prints RESULT pass only when no statement is violated and every statement was tested at
least once. "untested statement" is expected for phi = 1 (no stubs exist, so the stub check has
nothing to test) and for KOSARAK at phi 0.25 and 0.5 (no rescan happens, so the rescan check has
nothing to test); in those runs the remaining checks report 0 violations.

# Exhaustive check of PreLargeSeqTaSPM on random small streams, with fault injection (2026-11-17)

Command: `run.sh` in this directory, at commit `5862875` (`environment.txt`). The one modified path that
`environment.txt` counts is the untracked script `tools/verify/prelarge_stream_mutants.sh`, which this run
does not use. The output of the run without regularity pruning was lost by the file synchronisation of the
working copy (an empty file); it was produced again with the same command and the same classes, and is the
file kept here.

`tools/verify/java/PreLargeRandomCheck.java`: 2,000 random streams (seed 20261117; an initial database of 4
to 10 sequences, then 2 to 8 batches of 1 to 5 sequences), S_l = 0.5 S_u. After every batch the output is
compared with an exhaustive enumeration of the definition on the whole current database.

| buffer | snapshots | snapshots with a non-empty reference | reference patterns | safe updates | rescans | snapshots with a difference |
|---|---:|---:|---:|---:|---:|---:|
| irregular patterns dropped (default) | 12,052 | 11,169 | 178,601 | 5,620 | 4,432 | 0 |
| support only | 12,052 | 11,169 | 178,601 | 5,620 | 4,432 | 0 |

Both buffers take the same rescan decisions, because the rescan test depends on the accumulated batch bound and
not on the buffer content.

`tools/verify/prelarge_mutants.py` (`mutants.txt`): 10 mutants, 10 behave as expected.

* Controls, unchanged code, both buffers: pass.
* Faults, each caught (snapshots with a difference): bound not accumulated 36; batch bound SWUmax set to 0 (no
  rescan after D_init) 1,673; safety margin computed from twice the database size 105; support not updated
  4,600; last position not updated 651; regularity gap off by one 2,050.
* Benign changes, each passes as expected: never dropping a pattern from the buffer; ignoring the tail gap in the
  regularity test (the output filter applies the full regularity test).

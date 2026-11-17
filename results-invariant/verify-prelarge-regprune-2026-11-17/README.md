# Stream verification of PreLargeSeqTaSPM, default buffer, against RemineTaSPM (2026-11-17)

Command: `run.sh` in this directory, with the jar of commit `5862875` (`environment.txt`; clean tree). Arm
PreLargeSeqTaSPM with its default buffer: support at least S_l = 0.5 sigma, irregular patterns dropped
(`lower_ratio=0.5 rescan_test=SWUmax regularity_pruning=true` in every `.meta.txt`). After every snapshot
(D_init and the 100 increments) the pattern set and every support are compared exactly with those of
RemineTaSPM. Development machine (`machine.txt`), three runs at a time, 8 GB heap; times in these files are void.

| dataset | snapshots compared | snapshots with a difference | reference patterns summed over the snapshots | increments that rescanned | largest buffer (patterns) |
|---|---:|---:|---:|---:|---:|
| BMS1 | 101 / 101 | 0 | 17,765 | 45 / 100 | 450 |
| D48K-C10-T1-N5K | 101 / 101 | 0 | 3,488,924 | 38 / 100 | 271,144 |
| BIBLE | 101 / 101 | 0 | 494,693 | 100 / 100 | 7,103 |
| KOSARAK | 101 / 101 | 0 | 256,388 | 100 / 100 | 5,928 |
| SIGN | 101 / 101 | 0 | 11,177,498 | 33 / 100 | 31,098,303 |
| LEVIATHAN | 101 / 101 | 0 | 75,204,924 | 100 / 100 | 3,508,742 |
| FIFA | 101 / 101 | 0 | 954,506 | 82 / 100 | 28,330 |

707 of 707 snapshots agree. The reference sums equal those of the earlier stream verifications of the other
arms on the same configurations, so the same reference output was compared. Every run exited with 0
(`exit_codes.txt`). Increments that rescanned and largest buffer are read from the `rescans` and `live_nodes`
columns of the `.csv` files.

## Fault injection (`fault-injection/`)

`tools/verify/prelarge_stream_mutants.sh` compiles a copy of `PreLargeSeqTaSPM.java` with one textual fault,
puts it before the same jar on the class path and runs the same verification. `summary.txt` there gives, per
run, the snapshots compared, the snapshots with a difference and the increments that rescanned, so that a
fault that is not caught can be told apart from a fault that did not take effect.

| fault | dataset | snapshots compared | snapshots with a difference | increments that rescanned |
|---|---|---:|---:|---:|
| support of a buffered pattern not updated | BMS1 | 101 | 55 | 45 |
| batch bound SWUmax set to 0 | BMS1 | 101 | 0 | 0 |
| batch bound SWUmax set to 0 | D48K | 101 | 0 | 0 |
| batch bound SWUmax set to 0 | BIBLE | 101 | 0 | 0 |

The stream comparison catches a fault in the buffer update. The fault in the rescan test takes effect (no
rescan after D_init, against 45, 38 and 100 rescans of the correct code) but changes no output on these three
datasets: on them, every rescan of the correct code is a precaution that the output did not need. The rescan test
is therefore exercised only by the exhaustive check on random streams
(`results-invariant/prelarge-random-check-2026-11-17`), where the same fault gives 1,673 differing snapshots.

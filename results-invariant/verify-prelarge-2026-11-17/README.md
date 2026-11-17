# Stream verification of PreLargeSeqTaSPM against RemineTaSPM (2026-11-17), stopped early

Command: `run.sh` with the jar of commit `883d2c4` (buffer kept by support only; the regularity-pruning
option did not exist yet). Pattern sets and supports compared exactly after every snapshot.

| dataset | snapshots compared | differences | state |
|---|---:|---:|---|
| D48K-C10-T1-N5K | 101 / 101 | 0 | finished (`.csv`) |
| BMS1 | 56 / 101 | 0 | stopped by hand (`.csv.part`) |
| SIGN | 2 / 101 | 0 | stopped by hand; the initial buffer holds 21,968,107 patterns |
| BIBLE | 0 / 101 | - | stopped by hand during the initial mine |
| KOSARAK, LEVIATHAN, FIFA | not started | - | - |

The run was stopped after about 50 minutes because the buffer kept by support only is far larger than the
tracked set of the proposed method (SIGN: 21,968,107 buffered patterns after D_init; on BMS1 the buffer
grows to 490,749 patterns and the database is rescanned about every second increment), so the verification
of the slow datasets would not end within its 240-minute limit. The timing probe that decides between the
two buffers is in `results-probe/prelarge-probe-2026-11-17`. Times in these files are void.

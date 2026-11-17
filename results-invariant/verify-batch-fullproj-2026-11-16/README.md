# Stream verification of the batch baseline after the projection fix

Command: `run.sh` in this directory (benchmark jar sha256 `d0cf446b...`, build source commit `7d22b607`).
BatchTaSPM is compared with RemineTaSPM after every snapshot (initial database plus 100 increments):
exact pattern sets and supports.

| dataset | snapshots compared | reference patterns summed | differences |
|---|---:|---:|---:|
| BMS1 | 101 / 101 | 17,765 | 0 |
| D48K-C10-T1-N5K | 101 / 101 | 3,488,924 | 0 |
| BIBLE | 101 / 101 | 494,693 | 0 |
| SIGN | 101 / 101 | 11,177,498 | 0 |
| kosarak | 101 / 101 | 256,388 | 0 |
| LEVIATHAN | 101 / 101 | 75,204,924 | 0 |
| FIFA | 52 / 101 | 546,749 | 0 |

FIFA stopped at the 240-minute limit of the verify run before snapshot 52 (exit 5); snapshots 52 to 100
of FIFA are not verified. `summarize_verify.py` exits 1 for that reason.
Times in these files are void (four runs in parallel on the development machine).
The random-database check of the same jar is in `../batch-full-projection-2026-11-16/`.

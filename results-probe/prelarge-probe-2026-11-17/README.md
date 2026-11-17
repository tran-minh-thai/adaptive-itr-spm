# PROBE (not a measurement): pattern-level pre-large baseline, 2026-11-17

Development machine (Apple arm64, JDK 25.0.4.1), one repetition, no warm-up, 30-minute limit per stream,
16 GB heap, one run at a time. Jar of commit `17ee694` (environment.txt). These times decide feasibility only;
no number here goes into the paper.

Phase 1 (`run.sh`) was stopped by hand when the author chose the buffer that drops irregular patterns as the
baseline (files prefixed `stopped-phase1__`); phase 2 (`run2.sh`) ran that buffer first.

Total update time over the 100 increments (s):

| dataset | StaRSPM | Re-mine | Pre-large Seq., irregular patterns dropped | Pre-large Seq., buffer by support only |
|---|---:|---:|---:|---:|
| BMS1 | 0.1 | 2.5 | 4.3 (45 rescans) | > 669.8 after 57 increments; increment 58 unfinished after 80 min, stopped by hand |
| D48K | 0.6 | 15.3 | 113.7 (38 rescans) | not run |
| LEVIATHAN | 53.0 | 1,195.4 | > 1,759.9 after 21 increments (30-minute limit), a rescan at every increment | not run |

Buffer sizes: BMS1 249 patterns (irregular dropped) against 4,324,002 (support only) after 57 increments;
LEVIATHAN 3,266,696 patterns after 21 increments (irregular dropped).

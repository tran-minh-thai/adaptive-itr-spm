# Stream benchmark (`StreamBench`)

Every measured number comes from `com.adaptive.itr.spm.StreamBench`: one dataset, one arm and
one mode per JVM. The legacy orchestrators (`ExperimentRunner*`) and the per-class `run()`
methods are kept for reference and are not used for measured runs.

## 1. Stream

* Every non-empty line of the dataset file is one sequence and counts in `N`, also when it
  holds no target item. Lines without an item (two lines, in D45K and D48K) count in `N` too.
* `D_init` is the first `init_size` sequences in file order; the remaining sequences are cut,
  in file order, into `batch_count` increments. Increment `k` ends at
  `init_size + floor(k * (N_file - init_size) / batch_count)`, so the increments are equal
  when `batch_count` divides the stream and differ by at most one sequence otherwise.
* Batch 0 of a result file is the initial mine of `D_init`; batches 1..K are the increments.
* `min_sup = max(1, ceil(min_sup_rate * N))`, computed exactly in decimal arithmetic from the
  rate as written in the configuration file (`AppConfig.minSup`). Every arm uses this function.
* Only sequences that contain a target item are handed to the arms; the gap positions use the
  1-based line number of the sequence in the file.

| Dataset | Sequences | `init_size` | Increment (K = 100) | Source of `init_size` |
|---|---:|---:|---:|---|
| SIGN | 730 | 230 | 5 | OD.Num of Pre-HUSPM (Yan et al., 2025) |
| LEVIATHAN | 5,834 | 2,834 | 30 | OD.Num of Pre-HUSPM |
| FIFA | 20,450 | 10,450 | 100 | OD.Num of Pre-HUSPM |
| BIBLE | 36,369 | 21,369 | 150 | OD.Num of Pre-HUSPM |
| BMS1 | 59,601 | 39,601 | 200 | OD.Num of Pre-HUSPM |
| KOSARAK | 990,002 | 90,002 | 9,000 | as in the Pre-HUSPM-Adapt-Prelarge benchmark |
| D45K (not in the measurement plan) | 45,535 | 25,535 | 200 | stream of the last 20,000 sequences |
| D48K | 48,467 | 28,467 | 200 | stream of the last 20,000 sequences |
| KOSARAK_20/40/60/80 | 198,000 / 396,000 / 594,001 / 792,001 | 18,000 / 36,000 / 54,001 / 72,001 | 1,800 / 3,600 / 5,400 / 7,200 | stream = 100 * floor((10/11) N / 100), the rule that gives KOSARAK's split |

The configurations are `configs/stream_<dataset>.txt`. Batch-size sensitivity keeps the stream
and sets `--batch_count=25|50|67`.

## 2. Arms

| Arm | What it runs |
|---|---|
| `IncAdaTaSPM` | the method: incremental prefix tree with exact-count stubs (floor `phi * min_sup`, at least 2), lazy insertion, adaptive demotion threshold `LB = min_sup (1 - eta Score)` |
| `IncAdaTaSPM-NoBuffer` | the same code with `eta = 0` (`LB = min_sup`) |
| `IncAdaTaSPM-StaticPreLarge` | the same code with `Score = 1` for every pattern (`LB = (1 - eta) min_sup`, fixed) |
| `IncAdaTaSPM-Original` | the same code with `phi = 1` and lazy insertion off: the design procedure without stubs and lazy insertion |
| `IncAdaTaSPM-TBR` | the method with the target-proximity component in the score, weight `w2` |
| `RemineTaSPM` | mines the whole current database after every batch (the former `AdaIncTaSPM` class, which re-mined every batch) |
| `BatchTaSPM` | a pattern-growth batch miner over projected databases with a target filter and regularity check, re-run on the whole current database after every batch. A projected sequence keeps every itemset where the prefix ends, so I-extensions are found in every itemset that holds the prefix's last itemset (since commit `7d22b60`; the jar of the main campaign, `74be334f`, kept only the first such itemset and missed I-extensions that occur only later, see section 6) |
| `ItemPreLargeTaSPM` | self-built baseline: item-level pre-large bookkeeping with backward scans and a full re-mine over the tracked items after every batch (formerly `IncTaSPM_Li`; it is not an implementation of a published algorithm) |
| `PreLargeSeqTaSPM` | pattern-level pre-large baseline, the support form of Pre-HUSPM (utility 1 per sequence; S_u = min_sup_rate, S_l = 0.5 S_u, system property `prelarge.lower`): a buffer of the patterns with support at least S_l N; a safe update along the buffered patterns when buf + SWUmax(batch) <= f = (S_u - S_l) / (1 - S_u) N_rescan, otherwise a rescan of the whole current database. Irregular patterns are dropped from the buffer (author decision 2026-11-17; `-Dprelarge.regprune=false` keeps the buffer by support only, as in Pre-HUSPM) |

## 3. What the clock covers

`time_ms` is `System.nanoTime` around one call per batch.

| Arm | Inside the clock | Kept from the previous batch |
|---|---|---|
| `IncAdaTaSPM*` | appending the batch to `D_T` and to the item occurrence lists; tree walk on the batch; rescans; stub promotions; expansions; counting the output set (`countOutput`) | `D_T`, occurrence lists, prefix tree with stubs and tombstones |
| `RemineTaSPM` | building its arrays and per-sequence item cache for the whole current database; item scores; item bitmaps and occurrence lists; full DFS | nothing (the item cache is rebuilt for every sequence) |
| `BatchTaSPM` | building its filtered database for the whole current database; full DFS | nothing |
| `ItemPreLargeTaSPM` | copying the current database into its arrays; item-level pre-large update with backward scans; full DFS over the tracked items | item supports and item bitmaps of the tracked items |
| `PreLargeSeqTaSPM` | SWUmax of the batch; then either the safe update (projecting the batch along the buffered patterns, dropping patterns below S_l N or irregular) or a rescan (clearing the buffer and mining the whole current `D_T` at S_l N, projecting from the sequences themselves); counting the output set | the buffer (prefix tree of patterns with support, last position and largest gap), buf and N at the last rescan. It reads `D_T` from the list of target sequences that the harness appends to outside every clock, so it builds no copy of the database; Re-mine and Batch build theirs inside the clock. This favours the baseline |

Outside every clock, for every arm alike: reading and parsing the file (once, before the first
batch; `load_ms` in the run record), a forced collection and a 100 ms pause before each batch
(time mode), reading the counters after each batch.

## 4. Modes

* `--mode=time`: `--warmup` streams (default 1) are run and discarded, then measured
  repetitions, each a fresh instance of the arm in the same JVM. With `--reps=auto` the number
  of repetitions follows from the total time `T` of the first measured stream: `T < 1 s` 15,
  `T < 10 s` 10, `T < 120 s` 5, otherwise 3.
* `--mode=live`: a sampler thread forces a full collection, reads the used heap right after,
  and waits `max(20 ms, 4 x collection time)`; per batch `live_mb` is the largest reading during
  the batch and `retained_mb` the reading after it, both minus the used heap after loading the
  data. `live_mb` is a lower bound on the peak live set. Runtimes are void (`-3`).
* `--mode=verify`: the arm and `RemineTaSPM` run side by side; after every batch their pattern
  sets and supports are compared exactly (`cmp_*` columns, status `SAME` or `DIFF`). Exit code 3
  on any difference.

Exit codes: 0 done, 2 usage error, 3 verification difference, 4 the result file already exists
(the run is skipped, so a plan can be resumed), 5 a stream hit the time limit (`--timeout-min`,
default 120 per stream; in verify mode, of the whole run) or ran out of heap (the rows say `TIMEOUT` or `OOM`), 6 the JVM differs
from the runtime lock (`--lock`).

## 5. Result files

`<out>/<dataset>__<arm>[__<tag>]__<mode>.csv`, one row per batch and repetition, with
`<...>.meta.txt` (command, build stamp, jar and dataset SHA-256, effective parameters, JVM) and
`<out>/machine.txt`.

| Column | Meaning |
|---|---|
| `rep` | repetition; negative for warm-up streams |
| `batch` | 0 = initial mine of `D_init`, 1..K increments |
| `n_total`, `n_new` | sequences received so far, sequences in this batch (all lines) |
| `dt_total` | target sequences received so far |
| `min_sup` | the support threshold for this batch |
| `time_ms`, `cum_ms` | timed call for this batch, cumulative over the stream (time mode) |
| `patterns` | output patterns after the batch |
| `rescans`, `rescan_inserts`, `promotions` | engine: nodes re-projected on the full `D_T`, those of them that gained a tracked child, stubs promoted to nodes (per batch) |
| `projections` | engine and `RemineTaSPM`: projections computed in the batch (engine: `itemProj` and `extend` calls; re-mine: one per root candidate and per extension checked) |
| `live_nodes`, `tombstones`, `stubs`, `f_size` | engine tree after the batch; `f_size` = live nodes with `sup >= min_sup` |
| `backscans` | `ItemPreLargeTaSPM`: items whose exact support was rebuilt from `D_old` in the batch |
| `live_mb`, `retained_mb`, `gc_forced` | live mode |
| `cmp_ref_patterns`, `cmp_only_arm`, `cmp_only_ref`, `cmp_sup_diff` | verify mode |

`-1` means the counter does not exist for that arm.

## 6. Batch baseline re-measured after the projection fix (phase `fix-batch`, 2026-11-16)

The batch baseline of the main campaign (jar `74be334f`) projected each sequence from the first
itemset where the prefix ends, so it missed an I-extension that occurs only in a later itemset
holding the prefix's last itemset (`results-invariant/baseline-first-occurrence-2026-11-16`). The
fix keeps every such itemset (commit `7d22b60`, jar `d0cf446b`). Checks before measuring
(`results-invariant/batch-full-projection-2026-11-16`): the three small databases; 2000 random
databases with multi-item itemsets against exhaustive enumeration (0 differences for Batch,
Re-mine and the engine; the jar of the main campaign differs on 959); 40 of the 43 class files of
the main-campaign jar are byte-identical in the new jar, and only the four `BatchTaSPM` entries
and the build stamp differ, so every other arm runs the code it ran in the main campaign and is
not measured again. What the clock covers for `BatchTaSPM` (section 3) is unchanged.

Phase `fix-batch` of `bench/plan.txt`, per dataset in this order (BMS1, D48K, BIBLE, SIGN,
LEVIATHAN, KOSARAK, FIFA): the proposed method `IncAdaTaSPM-StaticPreLarge` in time mode (tag
`recheck3`), `BatchTaSPM` in time and live mode (tag `fullproj`), the proposed method in live
mode (tag `recheck3`). Same machine, JDK lock, heap (32g), `--reps=auto` and time limit (120
minutes per stream) as the main campaign.

Written before the runs:

* The output of the fixed Batch is the output of the old Batch on every dataset of the
  experiments: the verification of the old Batch against Re-mine found 0 differing batches on
  the seven datasets, and the fixed Batch is checked again
  (`results-invariant/verify-batch-fullproj-2026-11-16`). Only its time and memory change.
* Ratios that involve Batch (update time or memory of Batch / the proposed method) are computed
  from the two arms of phase `fix-batch`, measured in the same session, on every dataset. They
  are not formed with the proposed method of the main campaign.
* The drift of the proposed method between the main campaign and this session
  (`recheck3` against `main-time`) is reported per dataset, as for the supplementary session.
* FIFA: the old Batch hit the 120-minute limit in both modes; the fixed Batch does at least the
  same work, so a limit is expected again and the FIFA ratio stays a lower bound.

Outcome (campaign branch `campaign-stream100`, commit `1b8d873`; `tools/check_campaign.py`: 358 of 358
plan lines, 0 problems; analysis in `results/stream-bench-analysis/PC-THAI/`):

* Output: the final pattern count of the fixed Batch equals that of the old Batch on 7 of 7
  datasets; the stream verification against Re-mine compares 658 snapshots with 0 differences
  (`results-invariant/verify-batch-fullproj-2026-11-16`; FIFA covers snapshots 0..51 before the
  240-minute limit of the verification run).
* FIFA: the fixed Batch hit the 120-minute limit in both modes (time mode in the warm-up stream at
  batch 59, live mode at batch 49), as expected; the FIFA ratio is a lower bound.
* Drift of the proposed method, `recheck3` against `main-time`, update time: SIGN -0.05%,
  LEVIATHAN +1.48%, FIFA +3.78%, BIBLE +8.77%, BMS1 -2.65%, KOSARAK +0.18%, D48K -0.61%. BIBLE
  exceeds its within-session spread (0.2%).
* Analysis changed from the registration above (author's decision, 2026-11-17: both sessions run
  on one machine, Java runtime and heap, so they are not distinguished). Batch is taken from this
  phase and compared with the proposed method of the main campaign, like every other arm, in the
  tables, the ratios and the statistical tests; `recheck3` is kept as a record of the session
  drift only. With the pairing first registered (same session) the ratios were min 51.8, max 525,
  geometric mean 144; with the main-campaign proposed method they are 56.3, 526, 146. The
  minimum moves because of BIBLE (drift +8.77%); every ratio is above 50 in both analyses. On BMS1 the
  fixed Batch (5.22 s) and Item pre-large (5.44 s) are 4.0% apart; the text states no order
  between the two.
* The fixed Batch is not an extension of the old code path (projection stored as arrays of end
  itemsets instead of one start per sequence), so its time need not exceed the old one: fixed /
  old update time is 0.83 on BMS1 and 0.95 on KOSARAK, 1.06 to 1.15 elsewhere; its peak live set
  is larger on 5 of 7 datasets (KOSARAK 279 -> 505 MB) and smaller on BMS1 (2.24 -> 1.70 MB).
  The cause of the lower times on BMS1 and KOSARAK is not measured; BMS1 also varies between sessions.

## 7. Pattern-level pre-large baseline and support-threshold sweep (phases `prelarge` and `sigma`, registered 2026-11-18)

Jar built from a clean tree after commit `2727718` (`bench/adaptive-itr-spm-bench.jar.sha256`). The
new arm `PreLargeSeqTaSPM` (sections 2 and 3) is checked before measuring: 2000 random streams
against exhaustive enumeration, 12,052 snapshots, 0 differences for both buffers, 10 of 10 mutants
behave as expected (`results-invariant/prelarge-random-check-2026-11-17`); stream verification
against Re-mine on the seven datasets, 707 of 707 snapshots agree
(`results-invariant/verify-prelarge-regprune-2026-11-17`). The other arms run the classes they ran
in the main campaign (class-file comparison in the commit that adds the jar), so they are not
measured again.

Phase `prelarge`: `PreLargeSeqTaSPM` in time and live mode on the seven datasets at the
min_sup_rate of their configuration, shortest first (BMS1, D48K, BIBLE, FIFA, KOSARAK, SIGN,
LEVIATHAN). Phase `sigma`: on BMS1, D48K and LEVIATHAN, time mode, at twice and at half the
configured min_sup_rate (BMS1 0.002 and 0.0005, D48K 0.0018 and 0.00045, LEVIATHAN 0.01 and
0.0025; option `--min_sup_rate`, tag `sigma<value>`), for the proposed method
`IncAdaTaSPM-StaticPreLarge`, `RemineTaSPM` and `PreLargeSeqTaSPM`; the configured value is taken
from phase `main-time` (proposed method, Re-mine) and from phase `prelarge`. Same machine, JDK
lock, heap (32g), `--reps=auto` and time limit (120 minutes per stream) as the main campaign; a
run that hits the limit gives a lower bound, as in the main tables.

Expected directions, written before the runs (approved by the author, 2026-11-18). A direction
that the runs contradict is reported as a result.

1. The proposed method is faster than `PreLargeSeqTaSPM` on all seven datasets at the configured
   min_sup_rate.
2. `PreLargeSeqTaSPM` is slower than Re-mine on the datasets where it rescans at every increment
   (LEVIATHAN, BIBLE, KOSARAK; 100 of 100 increments in the stream verification).
3. As min_sup_rate decreases, the update-time ratio Re-mine / proposed method does not decrease,
   on each of the three datasets of the sweep.
4. The peak live set of `PreLargeSeqTaSPM` is larger than that of the proposed method on SIGN
   (its buffer reaches 31,098,303 patterns in the stream verification; the proposed method tracks
   1,674,167 nodes after the last increment).

Expected cost (extrapolated from the development-machine probe, not measured): `PreLargeSeqTaSPM`
at the configured value about 70 minutes per LEVIATHAN stream, near the limit; at half the value on
LEVIATHAN it mines at a quarter of it at each rescan and is expected to hit the limit.

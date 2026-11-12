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
| D45K | 45,535 | 25,535 | 200 | stream of the last 20,000 sequences |
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
| `BatchTaSPM` | a PrefixSpan-style batch miner with a target filter and regularity check, re-run on the whole current database after every batch |
| `ItemPreLargeTaSPM` | self-built baseline: item-level pre-large bookkeeping with backward scans and a full re-mine over the tracked items after every batch (formerly `IncTaSPM_Li`; it is not an implementation of a published algorithm) |

## 3. What the clock covers

`time_ms` is `System.nanoTime` around one call per batch.

| Arm | Inside the clock | Kept from the previous batch |
|---|---|---|
| `IncAdaTaSPM*` | appending the batch to `D_T` and to the item occurrence lists; tree walk on the batch; rescans; stub promotions; expansions; counting the output set (`countOutput`) | `D_T`, occurrence lists, prefix tree with stubs and tombstones |
| `RemineTaSPM` | building its arrays and per-sequence item cache for the whole current database; item scores; item bitmaps and occurrence lists; full DFS | nothing (the item cache is rebuilt for every sequence) |
| `BatchTaSPM` | building its filtered database for the whole current database; full DFS | nothing |
| `ItemPreLargeTaSPM` | copying the current database into its arrays; item-level pre-large update with backward scans; full DFS over the tracked items | item supports and item bitmaps of the tracked items |

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
default 120 per stream) or ran out of heap (the rows say `TIMEOUT` or `OOM`), 6 the JVM differs
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

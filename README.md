# Adaptive Incremental Target Regular Sequential Pattern Mining (Ada-ITR-SPM)

Reference implementation of **Ada-IncTaSPM** and five baseline algorithms
(**IncTaSPM_Li**, **IncStaticTaSPM**, **TalentNoReg**, **BatchTaSPM**,
**PrefixSpanTarget**) accompanying the submitted paper.

Released under the [MIT License](LICENSE) — free for academic and commercial use
with attribution.

---

## 0. Problem statement

Given

* a database `D` of transactional sequences (each sequence is an ordered list
  of itemsets, itemsets are sets of integer item ids — the same SPMF format
  produced by tools such as SPMF's dataset generator),
* a set of *target items* `T ⊆ I`,
* a relative minimum support `min_sup_rate ∈ (0, 1]`,
* a regularity bound `max_per` (integer, in transactions),

find every sequential pattern `P` such that

1. **Target-based**: at least one item of `P` belongs to `T`.
2. **Frequent**: `support(P, D) ≥ ⌈min_sup_rate · |D|⌉`.
3. **Regular**: the maximum gap between two consecutive occurrences of `P`
   (measured by transaction id) never exceeds `max_per`.

The database is not static — it is delivered in successive batches
(`data_chunks = [0.1, 0.1, …]` in the config files). After each batch,
the miner must update its pattern set **incrementally**, i.e. without
rescanning the earlier batches from scratch.

**Ada-IncTaSPM** solves this problem by scoring each item with a
convex combination of four signals

```
score(item) = (w1·Volume + w2·TBR + w3·Recency + w4·Stability) / 4
```

and relaxing the effective support threshold for high-scoring items:

```
min_sup_eff(item) = min_sup · (1 − eta · score(item))
```

This adaptive relaxation lets Ada-IncTaSPM rescue promising items whose
support is temporarily below `min_sup`, without paying for the exhaustive
backward scan or the fixed pre-large buffer used by earlier incremental
target-SPM baselines.

---

## 0.1. Algorithm catalogue

| Class                       | Family              | Incremental? | Regularity | Comment |
| --------------------------- | ------------------- | ------------ | ---------- | ------- |
| **AdaIncTaSPM** (proposed)  | pattern-growth + vertical hybrid | yes          | yes        | Adaptive per-item threshold; V9.0 fuses PrefixSpan-style local support counting into the vertical projection. |
| IncTaSPM_Li                 | pre-large + backscan             | yes          | yes        | Classical incremental target-SPM baseline; static `buffer_ratio`. |
| IncStaticTaSPM              | pre-large, in-memory             | yes          | yes        | Simpler variant of IncTaSPM_Li — no disk streaming. |
| TalentNoReg                 | projection + CoocMap             | no (batch)   | no         | Two-matrix (S / I) CoocMap; separates sequential vs. itemset extensions. |
| BatchTaSPM                  | vertical batch                   | no (batch)   | yes        | Rebuilds the full projected DB on every batch; I/O cost included. |
| PrefixSpanTarget            | PrefixSpan + target filter       | no (batch)   | yes        | Baseline PrefixSpan with a target-item filter bolted on. |

The three static baselines (Talent, BatchTaSPM, PrefixSpanTarget) deliberately
reload the whole prefix of the dataset that has arrived so far on every batch
and count that I/O in their wall time — this is what lets the paper
honestly quantify the cost of *not* being incremental.

---

## 0.2. Research questions

| RQ  | What it measures                                       | Orchestrator                                 |
| --- | ------------------------------------------------------ | -------------------------------------------- |
| RQ1 | Do the six algorithms differ in *runtime*?             | `ExperimentRunner`                           |
| RQ2 | Do they differ in *peak memory* and *pattern count*?   | `ExperimentRunner`                           |
| RQ3 | How sensitive is Ada-IncTaSPM to the recency decay λ?  | `ExperimentRunnerRQ3` and RQ3-scale          |
| RQ4 | Which of the four adaptive-score components matters?   | `ExperimentRunnerRQ4_Ablation`               |

The paper reports RQ1–RQ4 on eight public SPMF datasets:
BIBLE, BMS1_spmf, D45K, D48K, FIFA, kosarak, LEVIATHAN, SIGN.
See [`datasets/README.md`](datasets/README.md) for statistics and provenance.

---

## 1. Project layout

```
adaptive-itr-spm/
├── pom.xml                        Maven build descriptor
├── LICENSE                        MIT license text
├── README.md                      (this file)
├── scripts/                       Convenience launchers (Windows + POSIX)
│   ├── build.bat / build.sh       Build the fat jar
│   ├── run.bat / run.sh           Generic dispatcher (see §4)
│   ├── run-all.bat / run-all.sh   Run the full paper pipeline
│   ├── run-rq1.bat / run-rq1.sh   Baseline comparison (RQ1/RQ2)
│   ├── run-rq3.bat / run-rq3.sh   Parameter sensitivity (RQ3)
│   ├── run-rq3-scale.*            Kosarak scalability (RQ3-scale)
│   └── run-rq4.bat / run-rq4.sh   Ablation study (RQ4)
├── configs/                       All experiment configuration files (.txt)
├── datasets/                      Input datasets (download from SPMF, see §3)
├── results/                       Auto-created by run scripts; holds outputs
└── src/main/java/com/adaptive/itr/spm/
    ├── Main.java                  CLI dispatcher (jar entry-point)
    ├── AppConfig.java             Central config + path resolution
    ├── AdaIncTaSPM.java           Proposed algorithm (Ada-IncTaSPM)
    ├── IncTaSPM_Li.java           Baseline: Li et al. incremental (pre-large)
    ├── IncStaticTaSPM.java        Baseline: static-buffer incremental
    ├── TalentNoReg.java           Baseline: talent-style pure batch
    ├── BatchTaSPM.java            Baseline: batch target-SPM
    ├── PrefixSpanTarget.java      Baseline: PrefixSpan with target filter
    ├── ExperimentRunner.java              RQ1/RQ2 orchestrator
    ├── ExperimentRunnerRQ3.java           RQ3 orchestrator
    ├── ExperimentRunnerRQ3_SplitKosarak.java  RQ3 scalability orchestrator
    ├── ExperimentRunnerRQ4_Ablation.java  RQ4 orchestrator
    ├── GenerateRQ3Configs.java    Regenerate the RQ3 config files
    ├── GenerateRQ4Configs.java    Regenerate the RQ4 config files
    ├── ResultAggregator.java      Merges per-algorithm result_*.txt files
    ├── AlgorithmRegistry.java     Name → Runnable mapping
    └── CliArgs.java               Tiny CLI-flag parser
```

---

## 2. Prerequisites

* JDK 8 or newer (tested with JDK 17)
* Apache Maven 3.6+
* At least **8 GB RAM free** for BIBLE / kosarak / FIFA datasets
  (16 GB recommended — see `SPM_HEAP` env var below).

Build the fat jar:

```bash
# POSIX
./scripts/build.sh

# Windows
scripts\build.bat
```

The build produces `target/adaptive-itr-spm.jar` (a shaded, self-contained jar
whose main class is `com.adaptive.itr.spm.Main`).

---

## 3. Datasets

The eight paper datasets are bundled under [`datasets/`](datasets) so the
repository is self-contained for reproducibility. If you want to fetch fresh
copies (e.g. after normalising line-endings), the upstream URLs from SPMF are:

| Name                | Direct link |
| ------------------- | ----------- |
| BIBLE               | <https://www.philippe-fournier-viger.com/spmf/publicdatasets/BIBLE.txt> |
| BMS1_spmf           | <https://www.philippe-fournier-viger.com/spmf/publicdatasets/BMS1_spmf.txt> |
| D45K-C8-T1-N5K      | <https://www.philippe-fournier-viger.com/spmf/publicdatasets/data.slen_10.tlen_1.seq.patlen_6.lit.patlen_8.nitems_5000_spmf.txt> (rename to `D45K-C8-T1-N5K.txt`) |
| D48K-C10-T1-N5K     | <https://www.philippe-fournier-viger.com/spmf/publicdatasets/data.slen_8.tlen_1.seq.patlen_2.lit.patlen_8.nitems_5000_spmf.txt> (rename to `D48K-C10-T1-N5K.txt`) |
| FIFA                | <https://www.philippe-fournier-viger.com/spmf/publicdatasets/FIFA.txt> |
| kosarak             | <https://www.philippe-fournier-viger.com/spmf/publicdatasets/kosarak_sequences.txt> (rename to `kosarak.txt`) |
| LEVIATHAN           | <https://www.philippe-fournier-viger.com/spmf/publicdatasets/LEVIATHAN.txt> |
| SIGN                | <https://www.philippe-fournier-viger.com/spmf/publicdatasets/SIGN.txt> |

For the Kosarak scalability experiment the code additionally uses four prefix
cuts — `kosarak_20.txt`, `kosarak_40.txt`, `kosarak_60.txt`, `kosarak_80.txt`
— all bundled here; the 100 % scenario reuses `kosarak.txt` itself.

See [`datasets/README.md`](datasets/README.md) for further notes.

---

## 4. Running experiments

All commands go through the same dispatcher: `Main <command> [flags...]`.

### Full pipeline (all four research questions)

```bash
./scripts/run-all.sh
# or:
./scripts/run.sh all
```

### Individual experiments

```bash
# RQ1/RQ2 — baseline comparison across every dataset in configs/
./scripts/run-rq1.sh

# RQ3 — parameter sensitivity (λ grid over selected datasets)
./scripts/run-rq3.sh

# RQ3 scalability — increasing fractions of Kosarak
./scripts/run-rq3-scale.sh

# RQ4 — ablation over the four weight configurations
./scripts/run-rq4.sh
```

### Fine-grained overrides

Every run script forwards its CLI flags straight to the underlying runner:

```bash
# Only two datasets, only two algorithms
./scripts/run-rq1.sh --configs=config_bible.txt,config_kosarak.txt \
                     --algos=AdaIncTaSPM,IncTaSPM_Li

# RQ3 with a coarser λ grid
./scripts/run-rq3.sh --datasets=D48K --lambdas=0.1,0.5,0.9

# RQ3-scale on smaller cuts
./scripts/run-rq3-scale.sh --sizes=20,60,100

# Ablation with a single scenario
./scripts/run-rq4.sh --configs=config_RQ4_1_Full.txt --labels=1_Full_Proposed
```

### Direct `java -jar` invocation

If you prefer not to use the scripts:

```bash
java -Xmx16g -Dspm.home=/path/to/project \
     -jar target/adaptive-itr-spm.jar all
```

`-Dspm.home` tells `AppConfig` where to look for `configs/` and `datasets/`.

---

## 5. Where results go

The `run.sh` / `run.bat` wrappers `cd` into `results/` before launching the
jar, so every output file lands under `results/`:

* `result_<Algorithm>.txt` — per-algorithm raw log (recreated each dataset)
* `Summary_Report_<timestamp>_<dataset>_<chunks>.txt` — merged benchmark table
* RQ4 additionally suffixes the report filename with `_[<scenario>]`

The paper's reference outputs are already archived under
[`results/RQ1_RQ2_baseline`](results/RQ1_RQ2_baseline),
[`results/RQ3_scalability_kosarak`](results/RQ3_scalability_kosarak) and
[`results/RQ4_ablation`](results/RQ4_ablation) — see
[`results/README.md`](results/README.md). Fresh runs land at the top of
`results/` and never overwrite those archived subfolders.

Raw run logs are safe to delete; the summary reports are the artefacts that
back the paper tables.

---

## 6. Configuration files

Every config is a plain `key=value` text file in `configs/`. Typical keys:

```ini
dataset_path=D48K-C10-T1-N5K.txt   # relative to datasets/
data_chunks=0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1
min_sup_rate=0.0009
max_per=6000
target_items=4999999,3312329,4411672,2508874,3667146
buffer_ratio=0.1

# Ada-IncTaSPM weights (RQ4 varies these)
w1=0.25
w2=0.25
w3=0.25
w4=0.25
lambda=0.1
eta=0.2

# Benchmarking
total_runs=5
warmup_runs=2
```

`AppConfig.load()` looks up `dataset_path` first as-is, then under `datasets/`
next to `configs/`. Absolute paths are honoured verbatim.

---

## 7. Tuning heap and warm-up

* JVM heap is set by the launch scripts via `SPM_HEAP` (default `16g`):
  `SPM_HEAP=8g ./scripts/run-rq1.sh`
* Warm-up / measurement rounds are controlled per-config via
  `warmup_runs` and `total_runs`.

---

## 8. Citation

If you use this code, please cite the accompanying paper:

```
@article{AdaITRSPM202X,
  title  = {Adaptive Incremental Target Regular Sequential Pattern Mining},
  author = {…},
  year   = {202X}
}
```

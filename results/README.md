# Reference experiment outputs

This directory holds the **reference `Summary_Report_*.txt` files that back the
tables and figures of the paper**. They were produced by running the harness
against the datasets in [`../datasets/`](../datasets) with the configuration
files in [`../configs/`](../configs).

Layout:

```
results/
├── RQ1_RQ2_baseline/          # Baseline comparison across the 8 datasets
├── RQ3_scalability_kosarak/   # Kosarak scalability (5 size cuts)
├── RQ4_ablation/              # RQ4 weight-ablation (4 scenarios on D48K)
└── output_log.txt             # Raw console log of the final AdaIncTaSPM run
```

Fresh runs invoked through `scripts/run.sh` / `scripts/run.bat` are written
here at the top level (e.g. `Summary_Report_20260701_….txt`), so new runs
sit alongside the archived subfolders and do not overwrite them.

## RQ1 & RQ2 — Baseline comparison

For each of the 8 datasets, one summary report holds the runtime, peak RAM,
pruning ratio and pattern-count comparison table across all six algorithms
(Ada-IncTaSPM, IncTaSPM_Li, IncStaticTaSPM, TalentNoReg, BatchTaSPM,
PrefixSpanTarget) over the 10-batch incremental pipeline
(`data_chunks = 0.1×10`).

## RQ3 — Scalability

Ada-IncTaSPM run on Kosarak at 20 %, 40 %, 60 %, 80 % and 100 % of the raw
trace. Same 10-batch schedule; the file names embed the cut percentage.

## RQ4 — Ablation

Four Ada-IncTaSPM variants on D48K-C10-T1-N5K, each disabling a subset of the
adaptive scoring weights:

| Scenario | Config file                     | Disabled weight(s)     |
| -------- | ------------------------------- | ---------------------- |
| Full     | `config_RQ4_1_Full.txt`         | none (all four weights) |
| −TBR     | `config_RQ4_2_TBR.txt`          | w1 = 0 (Volume)         |
| −V,−R    | `config_RQ4_3_V_R.txt`          | w2 = w3 = 0 (Target proximity, Recency) |
| −S       | `config_RQ4_4_S.txt`            | w4 = 0 (Stability)      |

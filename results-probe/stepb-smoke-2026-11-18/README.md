# PROBE (not a measurement): smoke test of the jar for phases prelarge and sigma, 2026-11-18

Development machine, jar `8afa91f3` (commit `7e71346`), 4 GB heap, one stream each without warm-up
(`--reps=1 --warmup=0`), BMS1 only. Purpose: check that the plan options reach every arm before the
measurement machine runs them. Times here are void.

* `--min_sup_rate=0.002` is applied to the proposed method, Re-mine and PreLargeSeqTaSPM: the record
  gives `min_sup_rate=0.002`, the `min_sup` column after the last increment is 120 = ceil(0.002 x 59,601),
  and the three arms output the same 96 patterns.
* PreLargeSeqTaSPM in live mode at the configured value: 101 batches, exit 0, 126 patterns after the last
  increment, as in the stream verification.
* `tools/check_campaign.py` assigns the three sigma runs to the jar group `prelarge`.

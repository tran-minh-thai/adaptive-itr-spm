# Datasets

The eight paper datasets are bundled in this directory so the repository is
self-contained for reproducibility. Sourced from the SPMF public collection:

| Filename                        | Source URL |
| ------------------------------- | ---------- |
| `BIBLE.txt`                     | <https://www.philippe-fournier-viger.com/spmf/publicdatasets/BIBLE.txt> |
| `BMS1_spmf.txt`                 | <https://www.philippe-fournier-viger.com/spmf/publicdatasets/BMS1_spmf.txt> |
| `D45K-C8-T1-N5K.txt`            | <https://www.philippe-fournier-viger.com/spmf/publicdatasets/data.slen_10.tlen_1.seq.patlen_6.lit.patlen_8.nitems_5000_spmf.txt> |
| `D48K-C10-T1-N5K.txt`           | <https://www.philippe-fournier-viger.com/spmf/publicdatasets/data.slen_8.tlen_1.seq.patlen_2.lit.patlen_8.nitems_5000_spmf.txt> |
| `FIFA.txt`                      | <https://www.philippe-fournier-viger.com/spmf/publicdatasets/FIFA.txt> |
| `kosarak.txt`                   | <https://www.philippe-fournier-viger.com/spmf/publicdatasets/kosarak_sequences.txt> |
| `LEVIATHAN.txt`                 | <https://www.philippe-fournier-viger.com/spmf/publicdatasets/LEVIATHAN.txt> |
| `SIGN.txt`                      | <https://www.philippe-fournier-viger.com/spmf/publicdatasets/SIGN.txt> |

## Kosarak scalability slices

The RQ3 scalability experiment (`run-rq3-scale.sh`) additionally uses four
prefix cuts of `kosarak.txt` — `kosarak_20.txt`, `kosarak_40.txt`,
`kosarak_60.txt` and `kosarak_80.txt` — all bundled here. The `100 %` scenario
reuses `kosarak.txt` directly.

To regenerate the cuts from a fresh Kosarak trace:

```bash
# POSIX
TOTAL=$(wc -l < kosarak.txt)
for pct in 20 40 60 80; do
    head -n $((TOTAL * pct / 100)) kosarak.txt > kosarak_${pct}.txt
done
```

```powershell
# Windows PowerShell
$total = (Get-Content .\kosarak.txt).Count
foreach ($pct in 20,40,60,80) {
    Get-Content .\kosarak.txt -TotalCount ([int]($total * $pct / 100)) |
        Out-File ".\kosarak_$pct.txt" -Encoding ascii
}
```

## Dataset format

Every line is one sequence in SPMF format:

```
<item> [ <item> ... ] -1  [ <item> ... -1 ]  ...  -2
```

`-1` closes an itemset, `-2` closes the sequence. Comment lines start with `@`.

Example (a sequence with three itemsets):

```
1 10 -1 2 99 -1 5 6 -1 -2
```

## Dataset statistics (from the accompanying paper)

| Name              | Type            | |D|     | |I|    | avg |s| | Density   |
| ----------------- | --------------- | ------- | ------ | ------- | --------- |
| D48K-C10-T1-N5K   | Synthetic       | 48,467  | 5,000  | 10.0    | Sparse    |
| D45K-C8-T1-N5K    | Synthetic       | 45,535  | 5,000  | 8.0     | Sparse    |
| BMS1              | Web click-stream| 59,601  | 497    | 2.5     | Sparse    |
| FIFA              | Web click-stream| 20,450  | 2,990  | 34.74   | Sparse    |
| Kosarak           | Web click-stream| 990,000 | 41,270 | 8.14    | Very sparse|
| Bible             | Text            | 36,369  | 13,905 | 21.64   | Medium    |
| Leviathan         | Text            | 5,834   | 9,025  | 33.81   | Medium    |
| Sign              | Sign-language   | 730     | 267    | 51.99   | Very dense |

Top-10 most-frequent items (for target-item selection) — see `../configs/*.txt`.

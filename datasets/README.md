# Datasets

The data files are not redistributed here. `python tools/fetch_datasets.py` (from the repository
root) downloads them from the SPMF public dataset collection, builds the derived files and checks
every file against the SHA-256 in [`SOURCES.tsv`](SOURCES.tsv). Those checksums are the
`dataset_sha256` values recorded in the metadata of every measured run.

| File | Source | Transform |
| ---- | ------ | --------- |
| `BIBLE.txt` | <https://www.philippe-fournier-viger.com/spmf/publicdatasets/BIBLE.txt> | none |
| `BMS1_spmf.txt` | <https://www.philippe-fournier-viger.com/spmf/publicdatasets/BMS1_spmf.txt> | none |
| `D45K-C8-T1-N5K.txt` | <https://www.philippe-fournier-viger.com/spmf/publicdatasets/data.slen_8.tlen_1.seq.patlen_2.lit.patlen_8.nitems_5000_spmf.txt> | none (45,535 sequences) |
| `D48K-C10-T1-N5K.txt` | <https://www.philippe-fournier-viger.com/spmf/publicdatasets/data.slen_10.tlen_1.seq.patlen_6.lit.patlen_8.nitems_5000_spmf.txt> | none (48,467 sequences) |
| `FIFA.txt` | <https://www.philippe-fournier-viger.com/spmf/publicdatasets/FIFA.txt> | none |
| `LEVIATHAN.txt` | <https://www.philippe-fournier-viger.com/spmf/publicdatasets/LEVIATHAN.txt> | none |
| `SIGN.txt` | <https://www.philippe-fournier-viger.com/spmf/publicdatasets/SIGN.txt> | none |
| `kosarak.txt` | <https://www.philippe-fournier-viger.com/spmf/publicdatasets/kosarak_sequences.txt> | CRLF line ends replaced by LF |
| `kosarak_20.txt` … `kosarak_80.txt` | `kosarak.txt` | the first floor(990,002 × p / 100) lines, p = 20, 40, 60, 80 |

D48K and D45K are synthetic (IBM Quest generator); the others are real. The paper uses the seven
datasets other than D45K; D45K is kept for the exactness checks.

## Format

Every line is one sequence in SPMF format: items separated by spaces, `-1` closes an itemset,
`-2` closes the sequence, for example `1 10 -1 2 99 -1 5 6 -1 -2`.

Dataset properties and stream settings are produced by `tools/dataset_profile.py`
(`results-invariant/dataset-profile/`).

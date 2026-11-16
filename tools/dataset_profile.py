#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Profiles the datasets of the stream benchmark, read from the files and the stream configurations.

For every configs/stream_<name>.txt it reads the dataset file (SPMF format: items, -1 ends an
itemset, -2 ends a sequence) and writes one row with
  dataset, sha256, sequences, distinct items, mean itemsets per sequence, mean items per sequence,
  maximum items per sequence, init_size, batch_count, sequences per increment (min and max),
  min_sup_rate, max_per, target items, and for each target item its sequence support and its rank
  among all items by sequence support (rank 1 = most frequent; ties share the best rank).

Output: results-invariant/dataset-profile/datasets.tsv (+ profile_meta.txt with the command and the
SHA-256 of this tool). These are properties of the data, so the file does not depend on the machine.

Usage: tools/dataset_profile.py [--self-test]
"""
import collections
import hashlib
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIGS = ['sign', 'leviathan', 'fifa', 'bible', 'bms1', 'kosarak', 'D48K',
           'kosarak_20', 'kosarak_40', 'kosarak_60', 'kosarak_80']


def read_config(path):
    cfg = {}
    for line in open(path, encoding='utf-8'):
        line = line.strip()
        if line and not line.startswith('#') and '=' in line:
            k, v = line.split('=', 1)
            cfg[k.strip()] = v.strip()
    return cfg


def parse_line(line):
    """Returns the sequence as a list of itemsets, or None for a line without items."""
    seq, cur = [], []
    for tok in line.split():
        if tok == '-1':
            if cur:
                seq.append(cur)
            cur = []
        elif tok == '-2':
            break
        else:
            cur.append(int(tok))
    if cur:
        seq.append(cur)
    return seq


def profile_lines(lines):
    n, nitemsets, nitems, maxitems = 0, 0, 0, 0
    sup = collections.Counter()
    for line in lines:
        seq = parse_line(line)
        n += 1  # every line is a sequence position, also a line without items (as in the benchmark)
        nitemsets += len(seq)
        k = sum(len(s) for s in seq)
        nitems += k
        maxitems = max(maxitems, k)
        sup.update({x for s in seq for x in s})
    return n, sup, nitemsets, nitems, maxitems


def ranks(sup):
    """Competition rank by sequence support: ties share the best rank."""
    order = sorted(sup.values(), reverse=True)
    first = {}
    for i, v in enumerate(order):
        first.setdefault(v, i + 1)
    return {x: first[v] for x, v in sup.items()}


def increments(total, init, k):
    stream = total - init
    ends = [init + (j * stream) // k for j in range(k + 1)]
    sizes = [b - a for a, b in zip(ends, ends[1:])]
    return min(sizes), max(sizes)


def self_test():
    ok = 0
    lines = ['1 2 -1 3 -1 -2', '2 -1 -2', '', '3 3 -1 1 -1 -2']
    n, sup, ni, nit, mx = profile_lines(lines)
    ok += (n, ni, nit, mx) == (4, 5, 7, 3)                     # empty line counted as a sequence
    ok += dict(sup) == {1: 2, 2: 2, 3: 2}                       # sequence support, not occurrences
    r = ranks(collections.Counter({7: 5, 8: 5, 9: 1}))
    ok += r == {7: 1, 8: 1, 9: 3}                               # ties share the best rank
    ok += increments(730, 230, 100) == (5, 5) and increments(10, 0, 3) == (3, 4)
    print('self-test: %d of 4 checks passed' % ok)
    return ok == 4


def main():
    if '--self-test' in sys.argv:
        sys.exit(0 if self_test() else 2)
    out_dir = os.path.join(ROOT, 'results-invariant', 'dataset-profile')
    os.makedirs(out_dir, exist_ok=True)
    cols = ['config', 'dataset', 'sha256', 'sequences', 'items', 'mean_itemsets', 'mean_items', 'max_items',
            'init_size', 'batch_count', 'inc_min', 'inc_max', 'min_sup_rate', 'max_per', 'targets',
            'target_support', 'target_rank']
    rows = []
    for c in CONFIGS:
        cfg = read_config(os.path.join(ROOT, 'configs', 'stream_%s.txt' % c))
        path = os.path.join(ROOT, 'datasets', cfg['dataset_path'])
        raw = open(path, 'rb').read()
        lines = raw.decode('utf-8').splitlines()
        n, sup, ni, nit, mx = profile_lines(lines)
        rk = ranks(sup)
        tg = [int(x) for x in cfg['target_items'].split(',')]
        init, k = int(cfg['init_size']), int(cfg.get('batch_count', '100'))
        lo, hi = increments(n, init, k)
        rows.append([c, cfg['dataset_path'], hashlib.sha256(raw).hexdigest(), n, len(sup), '%.2f' % (ni / n),
                     '%.2f' % (nit / n), mx, init, k, lo, hi, cfg['min_sup_rate'], cfg['max_per'],
                     ','.join(map(str, tg)), ','.join(str(sup.get(x, 0)) for x in tg),
                     ','.join(str(rk.get(x, 0)) for x in tg)])
        print('%-11s seqs %7d items %6d targets %s ranks %s' % (c, n, len(sup), rows[-1][14], rows[-1][16]))
    with open(os.path.join(out_dir, 'datasets.tsv'), 'w', encoding='utf-8', newline='\n') as w:
        w.write('\t'.join(cols) + '\n')
        for r in rows:
            w.write('\t'.join(map(str, r)) + '\n')
    with open(os.path.join(out_dir, 'profile_meta.txt'), 'w', encoding='utf-8', newline='\n') as w:
        w.write('command=python3 tools/dataset_profile.py\n')
        w.write('tool_sha256=%s\n' % hashlib.sha256(open(os.path.abspath(__file__), 'rb').read()).hexdigest())
        w.write('python=%s\n' % sys.version.split()[0])


if __name__ == '__main__':
    main()

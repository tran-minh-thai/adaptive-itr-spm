#!/usr/bin/env python3
"""Statistical comparison of the arms of the main phases, from the output of extract_campaign.py.

Writes into the analysis directory:
  stats_friedman.tsv  per metric: Friedman chi-square and p, Iman-Davenport F and p, Nemenyi
                      critical difference (alpha 0.05), average rank of every arm
  stats_wilcoxon_datasets.tsv  per metric: the proposed arm against every other arm over the
                      datasets (Wilcoxon signed-rank, exact two-sided), with Holm-adjusted p
  stats_wilcoxon_batches.tsv  per dataset: the proposed arm against every other arm over the
                      K update batches (median batch time over repetitions, paired by batch)
  stats_meta.txt      command, SHA-256 of this tool and of the inputs

Rules (fixed before looking at the outcome):
  * metric 'update_ms': median over repetitions of the summed update time (batches 1..K);
    'total_ms' adds the initial mine; 'peak_live_mb' is the main-live peak live set.
  * a run that hit the 120-minute limit has no measured value. For ranks it is placed after every
    finished arm of the dataset (it ran longer than any of them); runs that hit the limit on the
    same dataset share their ranks. For the Wilcoxon test over datasets it enters with the lower
    bound 120 min (7,200,000 ms), so its difference to the proposed arm is a lower bound.
  * ties in ranks get the average rank; lower values are better for every metric.
  * Wilcoxon over batches is not computed for a pair with a run that hit the limit.

Usage: tools/stats_campaign.py <analysis dir>   |   tools/stats_campaign.py --self-test
"""
import csv
import hashlib
import math
import os
import sys

import numpy as np
from scipy import stats

PROPOSED = 'IncAdaTaSPM'
ARMS = ['IncAdaTaSPM', 'IncAdaTaSPM-NoBuffer', 'IncAdaTaSPM-StaticPreLarge', 'IncAdaTaSPM-Original',
        'RemineTaSPM', 'BatchTaSPM', 'ItemPreLargeTaSPM']
LIMIT_MS = 120 * 60 * 1000.0
# Studentized range statistic q_alpha / sqrt(2) for alpha = 0.05 (Demsar, 2006, Table 5a).
Q05 = {2: 1.960, 3: 2.343, 4: 2.569, 5: 2.728, 6: 2.850, 7: 2.949, 8: 3.031, 9: 3.102, 10: 3.164}


def ranks_with_limits(values):
    """values: list of float or None (None = hit the limit). Returns average ranks, 1 = best."""
    fin = [(v, i) for i, v in enumerate(values) if v is not None]
    lim = [i for i, v in enumerate(values) if v is None]
    r = [0.0] * len(values)
    fr = stats.rankdata([v for v, _ in fin]) if fin else []
    for (v, i), x in zip(fin, fr):
        r[i] = float(x)
    if lim:
        start = len(fin) + 1
        avg = (start + len(values)) / 2.0
        for i in lim:
            r[i] = avg
    return r


def friedman(table):
    """table: N x k list of lists (None = limit). Returns chi2, p, F, pF, average ranks, CD."""
    R = np.array([ranks_with_limits(row) for row in table])
    N, k = R.shape
    avg = R.mean(axis=0)
    chi2 = 12.0 * N / (k * (k + 1)) * (np.sum(avg ** 2) - k * (k + 1) ** 2 / 4.0)
    # tie correction
    ties = 0.0
    for row in R:
        _, c = np.unique(row, return_counts=True)
        ties += np.sum(c ** 3 - c)
    chi2 /= 1.0 - ties / (N * k * (k * k - 1))
    p = stats.chi2.sf(chi2, k - 1)
    F = (N - 1) * chi2 / (N * (k - 1) - chi2) if N * (k - 1) - chi2 > 0 else float('inf')
    pF = stats.f.sf(F, k - 1, (k - 1) * (N - 1))
    cd = Q05[k] * math.sqrt(k * (k + 1) / (6.0 * N))
    return chi2, p, F, pF, avg, cd


def holm(ps):
    order = sorted(range(len(ps)), key=lambda i: ps[i])
    adj = [0.0] * len(ps)
    run = 0.0
    m = len(ps)
    for j, i in enumerate(order):
        run = max(run, min(1.0, (m - j) * ps[i]))
        adj[i] = run
    return adj


def wilcoxon(a, b):
    d = np.asarray(a, float) - np.asarray(b, float)
    if np.all(d == 0):
        return float('nan'), 1.0
    res = stats.wilcoxon(a, b, alternative='two-sided', method='auto', zero_method='wilcox')
    return float(res.statistic), float(res.pvalue)


def load(d):
    runs = list(csv.DictReader(open(os.path.join(d, 'runs.tsv'), encoding='utf-8'), delimiter='\t'))
    bat = list(csv.DictReader(open(os.path.join(d, 'batches.tsv'), encoding='utf-8'), delimiter='\t'))
    return runs, bat


def value(r, metric):
    if r['status'] != 'OK' or r.get(metric, '') == '':
        return None
    return float(r[metric])


def main(d):
    runs, bat = load(d)
    out_f, out_w, out_b = [], [], []
    for metric, phase in (('update_ms_med', 'main-time'), ('total_ms_med', 'main-time'), ('peak_live_mb', 'main-live')):
        sel = {(r['dataset'], r['arm']): r for r in runs if r['phase'] == phase}
        dss = sorted({k[0] for k in sel})
        table = [[value(sel[(ds, a)], metric) for a in ARMS] for ds in dss]
        if any(len(row) != len(ARMS) for row in table) or len(dss) == 0:
            sys.exit('incomplete table for ' + metric)
        chi2, p, F, pF, avg, cd = friedman(table)
        rec = {'metric': metric, 'datasets': len(dss), 'arms': len(ARMS), 'limit_cells': sum(v is None for row in table for v in row),
               'chi2': chi2, 'p': p, 'iman_davenport_F': F, 'p_F': pF, 'nemenyi_cd_05': cd}
        rec.update({'rank_' + a: x for a, x in zip(ARMS, avg)})
        out_f.append(rec)
        pi = ARMS.index(PROPOSED)
        ps, rows = [], []
        for j, a in enumerate(ARMS):
            if j == pi:
                continue
            x = [row[pi] for row in table]
            y = [row[j] if row[j] is not None else LIMIT_MS for row in table]
            if any(v is None for v in x):
                sys.exit('the proposed arm hit the limit; the over-datasets test is undefined')
            W, pw = wilcoxon(x, y)
            better = sum(1 for u, v in zip(x, y) if u < v)
            worse = sum(1 for u, v in zip(x, y) if u > v)
            ratios = [v / u for u, v in zip(x, y) if u > 0]
            rows.append({'metric': metric, 'against': a, 'datasets': len(x), 'proposed_lower': better,
                         'proposed_higher': worse, 'limit_cells_lower_bound': sum(row[j] is None for row in table),
                         'W': W, 'p': pw, 'ratio_other_over_proposed_geomean': float(np.exp(np.mean(np.log(ratios)))),
                         'ratio_min': min(ratios), 'ratio_max': max(ratios)})
            ps.append(pw)
        for r_, a_ in zip(rows, holm(ps)):
            r_['p_holm'] = a_
        out_w += rows
    # per dataset, over batches
    bt = {}
    for b in bat:
        if b['tag'] == '-' and int(b['batch']) >= 1:
            bt.setdefault((b['dataset'], b['arm']), {})[int(b['batch'])] = float(b['time_ms_med'])
    main_runs = {(r['dataset'], r['arm']): r for r in runs if r['phase'] == 'main-time'}
    for ds in sorted({k[0] for k in main_runs}):
        rows, ps = [], []
        for a in ARMS:
            if a == PROPOSED:
                continue
            if main_runs[(ds, a)]['status'] != 'OK':
                rows.append({'dataset': ds, 'against': a, 'batches': 0, 'note': 'limit hit, not tested'})
                continue
            P, O = bt[(ds, PROPOSED)], bt[(ds, a)]
            ks = sorted(set(P) & set(O))
            x, y = [P[k] for k in ks], [O[k] for k in ks]
            W, pw = wilcoxon(x, y)
            rows.append({'dataset': ds, 'against': a, 'batches': len(ks),
                         'proposed_lower': sum(u < v for u, v in zip(x, y)), 'proposed_higher': sum(u > v for u, v in zip(x, y)),
                         'W': W, 'p': pw, 'median_batch_ratio_other_over_proposed': float(np.median([v / u for u, v in zip(x, y) if u > 0])),
                         'note': ''})
            ps.append(pw)
        tested = [r for r in rows if r['batches']]
        for r_, a_ in zip(tested, holm(ps)):
            r_['p_holm'] = a_
        out_b += rows

    def write(name, data):
        keys = []
        for r in data:
            for k in r:
                if k not in keys:
                    keys.append(k)
        with open(os.path.join(d, name), 'w', newline='\n', encoding='utf-8') as w:
            w.write('\t'.join(keys) + '\n')
            for r in data:
                w.write('\t'.join(fmt(r.get(k, '')) for k in keys) + '\n')

    write('stats_friedman.tsv', out_f)
    write('stats_wilcoxon_datasets.tsv', out_w)
    write('stats_wilcoxon_batches.tsv', out_b)
    with open(os.path.join(d, 'stats_meta.txt'), 'w', newline='\n', encoding='utf-8') as w:
        w.write('command=tools/stats_campaign.py <analysis dir>\n')
        w.write('tool_sha256=%s\n' % sha(os.path.abspath(__file__)))
        for f in ('runs.tsv', 'batches.tsv'):
            w.write('input %s %s\n' % (sha(os.path.join(d, f)), f))
        w.write('numpy=%s scipy=%s\n' % (np.__version__, __import__('scipy').__version__))
    print('friedman rows=%d wilcoxon_datasets rows=%d wilcoxon_batches rows=%d' % (len(out_f), len(out_w), len(out_b)))


def fmt(v):
    if isinstance(v, float):
        return '%.6g' % v
    return str(v)


def sha(p):
    return hashlib.sha256(open(p, 'rb').read()).hexdigest()


def self_test():
    rng = np.random.default_rng(1)
    fails = 0
    # 1. Friedman without ties agrees with scipy
    t = rng.random((7, 7)).tolist()
    chi2 = friedman(t)[0]
    ref = stats.friedmanchisquare(*np.array(t).T).statistic
    ok = abs(chi2 - ref) < 1e-9
    print('friedman vs scipy (no ties): %s (%.6f vs %.6f)' % ('ok' if ok else 'FAIL', chi2, ref)); fails += not ok
    # 2. with ties (scipy applies the same tie correction)
    t2 = np.round(rng.random((7, 7)) * 3).tolist()
    chi2b = friedman(t2)[0]
    refb = stats.friedmanchisquare(*np.array(t2).T).statistic
    ok = abs(chi2b - refb) < 1e-9
    print('friedman vs scipy (ties): %s (%.6f vs %.6f)' % ('ok' if ok else 'FAIL', chi2b, refb)); fails += not ok
    # 3. limit cells rank after every finished value, shared among themselves
    r = ranks_with_limits([5.0, None, 1.0, None, 3.0])
    ok = r == [3.0, 4.5, 1.0, 4.5, 2.0]
    print('ranks with limits: %s %s' % ('ok' if ok else 'FAIL', r)); fails += not ok
    # 4. a limit cell is ranked worse even when the finished values are large
    r = ranks_with_limits([1e12, None])
    ok = r == [1.0, 2.0]
    print('limit ranked last: %s %s' % ('ok' if ok else 'FAIL', r)); fails += not ok
    # 5. Demsar (2006) critical difference, k = 4, N = 14: 1.25
    cd = Q05[4] * math.sqrt(4 * 5 / (6.0 * 14))
    ok = abs(cd - 1.25) < 0.01
    print('nemenyi CD k=4 N=14: %s (%.3f)' % ('ok' if ok else 'FAIL', cd)); fails += not ok
    # 6. Holm
    ok = np.allclose(holm([0.01, 0.04, 0.03]), [0.03, 0.06, 0.06])
    print('holm: %s %s' % ('ok' if ok else 'FAIL', holm([0.01, 0.04, 0.03]))); fails += not ok
    # 7. a consistent winner is detected; a column of identical values is not separated
    t3 = [[1, 2, 3, 4, 5, 6, 7]] * 7
    _, p3, _, _, avg3, _ = friedman(t3)
    ok = p3 < 1e-6 and list(avg3) == [1, 2, 3, 4, 5, 6, 7]
    print('consistent ordering detected: %s (p=%.2g)' % ('ok' if ok else 'FAIL', p3)); fails += not ok
    # 8. Wilcoxon exact with 7 pairs all in one direction: p = 2/128
    _, pw = wilcoxon([1, 2, 3, 4, 5, 6, 7], [2, 4, 6, 8, 10, 12, 14])
    ok = abs(pw - 2 / 128) < 1e-12
    print('wilcoxon 7 same-sign pairs: %s (p=%.6f)' % ('ok' if ok else 'FAIL', pw)); fails += not ok
    print('self-test failures=%d' % fails)
    sys.exit(1 if fails else 0)


if __name__ == '__main__':
    if sys.argv[1:] == ['--self-test']:
        self_test()
    main(sys.argv[1])

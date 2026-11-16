#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Tests the four hypotheses registered before the supplementary campaign on the proposed arm.

Reads runs.tsv written by tools/extract_campaign.py and prints, for every hypothesis, one line per
dataset with the numbers it rests on, the number of comparisons that could be made, and a verdict.

  H1  session drift: |update time of sup-recheck / update time of the main run - 1| <= 3% per run.
  H2  the update-time ratio Re-mine / proposed increases with the number of batches K
      (K in 25, 50, 67 from sup-batch-count and batch-count, K = 100 from main-time).
  H3  phi = 1 is slower than phi = 0.5 on every dataset; phi = 0.25 is faster than phi = 0.5 on
      BIBLE, FIFA, BMS1 and LEVIATHAN. A difference inside the tie band (the larger of the two
      runs' relative min-max spread over repetitions, and the measured session drift of that
      dataset) counts as a tie, not as a win.
  H4  on SIGN and LEVIATHAN a larger eta keeps more live nodes (final and maximum over the stream).
      The memory half of H4 cannot be tested: the eta sweep ran in time mode only, which records
      no live set. This is printed, not skipped silently.

Usage: tools/check_supplementary.py <runs.tsv> [--self-test]
Exit code 0 when every hypothesis could be evaluated (verdicts may be "rejected"), 1 when a needed
run is missing, 2 when the self-test fails.
"""
import copy
import csv
import sys

PROPOSED = 'IncAdaTaSPM-StaticPreLarge'
REMINE = 'RemineTaSPM'
DRIFT_LIMIT = 0.03
H3_FASTER_SET = ['BIBLE', 'FIFA', 'BMS1_spmf', 'LEVIATHAN']
H4_SET = ['SIGN', 'LEVIATHAN']


def load(path):
    with open(path, newline='', encoding='utf-8') as f:
        return list(csv.DictReader(f, delimiter='\t'))


class Missing(Exception):
    pass


def get(rows, phase, ds, arm, tag='-', mode='time'):
    hit = [r for r in rows if r['phase'] == phase and r['dataset'] == ds and r['arm'] == arm
           and r['tag'] == tag and r['mode'] == mode]
    if len(hit) != 1:
        raise Missing('%d rows for %s %s %s %s %s' % (len(hit), phase, ds, arm, tag, mode))
    if hit[0]['status'] != 'OK':
        raise Missing('status %s for %s %s %s %s' % (hit[0]['status'], phase, ds, arm, tag))
    return hit[0]


def upd(r):
    return float(r['update_ms_med'])


def spread(r):
    return (float(r['update_ms_max']) - float(r['update_ms_min'])) / float(r['update_ms_med'])


def evaluate(rows):
    out, verdict = [], {}
    datasets = sorted({r['dataset'] for r in rows if r['phase'] == 'sup-phi'})
    # H1
    drift, n, bad = {}, 0, []
    for r in rows:
        if r['phase'] != 'sup-recheck':
            continue
        # the KOSARAK prefixes were first measured in the kosarak-scale phase, the rest in main-time
        main = get(rows, 'kosarak-scale' if r['dataset'].startswith('kosarak_') else 'main-time', r['dataset'], r['arm'])
        d = upd(r) / upd(main) - 1
        drift.setdefault(r['dataset'], []).append(abs(d))
        n += 1
        if abs(d) > DRIFT_LIMIT:
            bad.append(r['dataset'] + '/' + r['arm'])
        out.append('H1 %-16s %-27s main %10.1f ms  recheck %10.1f ms  drift %+6.2f%%' %
                   (r['dataset'], r['arm'], upd(main), upd(r), 100 * d))
    verdict['H1'] = ('supported' if not bad else 'rejected for ' + ', '.join(bad)) + ' (%d runs compared)' % n
    # H2
    n, bad = 0, []
    for ds in datasets:
        ratios = []
        for k in ('K25', 'K50', 'K67'):
            ratios.append(upd(get(rows, 'batch-count', ds, REMINE, k)) / upd(get(rows, 'sup-batch-count', ds, PROPOSED, k)))
        ratios.append(upd(get(rows, 'main-time', ds, REMINE)) / upd(get(rows, 'main-time', ds, PROPOSED)))
        inc = all(b > a for a, b in zip(ratios, ratios[1:]))
        n += 1
        if not inc:
            bad.append(ds)
        out.append('H2 %-16s ratio at K = 25, 50, 67, 100: %s  %s' %
                   (ds, ', '.join('%.2f' % x for x in ratios), 'increasing' if inc else 'NOT increasing'))
    verdict['H2'] = ('supported' if not bad else 'rejected for ' + ', '.join(bad)) + ' (%d datasets)' % n
    # H3
    n, bad1, bad2, ties = 0, [], [], []
    for ds in datasets:
        base = get(rows, 'main-time', ds, PROPOSED)
        band_drift = max(drift.get(ds, [0.0]))
        for tag, want in (('phi1', 'slower'), ('phi0.25', 'faster')):
            r = get(rows, 'sup-phi', ds, PROPOSED, tag)
            rel = upd(r) / upd(base) - 1
            band = max(spread(r), spread(base), band_drift)
            res = 'tie' if abs(rel) <= band else ('slower' if rel > 0 else 'faster')
            out.append('H3 %-16s %-7s vs phi0.5: %+7.2f%% (tie band %.2f%%) -> %s' % (ds, tag, 100 * rel, 100 * band, res))
            if tag == 'phi1':
                n += 1
                if res != 'slower':
                    bad1.append('%s (%s)' % (ds, res))
            elif ds in H3_FASTER_SET:
                n += 1
                if res != 'faster':
                    bad2.append('%s (%s)' % (ds, res))
            if res == 'tie':
                ties.append(ds + '/' + tag)
    verdict['H3a'] = ('supported' if not bad1 else 'rejected for ' + ', '.join(bad1)) + ' (phi = 1 vs 0.5, %d datasets)' % len(datasets)
    verdict['H3b'] = ('supported' if not bad2 else 'rejected for ' + ', '.join(bad2)) + ' (phi = 0.25 vs 0.5, %d datasets)' % len(H3_FASTER_SET)
    # H4
    bad = []
    for ds in H4_SET:
        pts = []
        for e in ('0', '0.1', '0.3'):
            pts.append((float(e), get(rows, 'sup-eta', ds, PROPOSED, 'eta' + e)))
        pts.append((0.5, get(rows, 'main-time', ds, PROPOSED)))
        for e in ('0.7', '0.9'):
            pts.append((float(e), get(rows, 'sup-eta', ds, PROPOSED, 'eta' + e)))
        for col in ('final_live_nodes', 'max_live_nodes'):
            v = [int(p[1][col]) for p in pts]
            mono = all(b >= a for a, b in zip(v, v[1:]))
            out.append('H4 %-16s %-16s eta 0..0.9: %s  %s' % (ds, col, ', '.join(str(x) for x in v),
                                                             'non-decreasing' if mono else 'NOT non-decreasing'))
            if not mono:
                bad.append(ds + '/' + col)
        t = [upd(p[1]) for p in pts]
        out.append('H4 %-16s update ms        eta 0..0.9: %s' % (ds, ', '.join('%.0f' % x for x in t)))
    verdict['H4'] = ('supported for nodes' if not bad else 'rejected for ' + ', '.join(bad)) + \
        ' (%d datasets x 2 counters); memory half not testable (eta sweep has no live-mode runs)' % len(H4_SET)
    return out, verdict


def self_test(rows):
    """Each injected fault must flip exactly the verdict it targets."""
    def run(mut):
        r = copy.deepcopy(rows)
        mut(r)
        return evaluate(r)[1]

    def setv(r, phase, ds, arm, tag, col, f):
        for x in r:
            if x['phase'] == phase and x['dataset'] == ds and x['arm'] == arm and x['tag'] == tag and x['mode'] == 'time':
                x[col] = str(f(float(x[col])))

    base = evaluate(rows)[1]
    cases = [
        ('H1', lambda r: setv(r, 'sup-recheck', 'BIBLE', PROPOSED, 'recheck', 'update_ms_med', lambda v: v * 1.10)),
        ('H2', lambda r: setv(r, 'batch-count', 'SIGN', REMINE, 'K67', 'update_ms_med', lambda v: v * 10)),
        ('H3a', lambda r: setv(r, 'sup-phi', 'FIFA', PROPOSED, 'phi1', 'update_ms_med', lambda v: v / 100)),
        ('H3b', lambda r: setv(r, 'sup-phi', 'BIBLE', PROPOSED, 'phi0.25', 'update_ms_med', lambda v: v * 100)),
        ('H4', lambda r: setv(r, 'sup-eta', 'SIGN', PROPOSED, 'eta0.9', 'max_live_nodes', lambda v: 1)),
    ]
    ok = 0
    for key, mut in cases:
        got = run(mut)
        changed = [k for k in base if base[k] != got[k]]
        good = changed == [key]
        ok += good
        print('self-test %-4s changed %s -> %s' % (key, changed, 'ok' if good else 'FAIL'))
    print('self-test: %d of %d faults caught by exactly their own verdict' % (ok, len(cases)))
    return ok == len(cases)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    rows = load(sys.argv[1])
    if '--self-test' in sys.argv:
        sys.exit(0 if self_test(rows) else 2)
    try:
        out, verdict = evaluate(rows)
    except Missing as e:
        print('missing run: %s' % e)
        sys.exit(1)
    print('\n'.join(out))
    print()
    for k in sorted(verdict):
        print('%s: %s' % (k, verdict[k]))


if __name__ == '__main__':
    main()

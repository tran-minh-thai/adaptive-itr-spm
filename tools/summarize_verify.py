#!/usr/bin/env python3
"""Summarises a directory of StreamBench verify runs into summary.tsv (one row per dataset and
arm) and prints it. Exit code 1 if a run is missing, unfinished, or has a differing batch.

Usage: tools/summarize_verify.py <results directory>
"""
import csv
import glob
import os
import sys

d = sys.argv[1]
rows = []
bad = 0
parts = sorted(glob.glob(os.path.join(d, '*__verify.csv.part')))
for p in parts:
    rows.append({'file': os.path.basename(p), 'status': 'UNFINISHED'})
    bad += 1
for f in sorted(glob.glob(os.path.join(d, '*__verify.csv'))):
    r = list(csv.DictReader(open(f, newline='')))
    ok = [x for x in r if x['status'] in ('SAME', 'DIFF')]
    stop = [x['status'] for x in r if x['status'] not in ('SAME', 'DIFF')]
    diff = sum(1 for x in ok if x['status'] == 'DIFF')
    def tot(k):
        v = [int(x[k]) for x in ok[1:] if x[k] not in ('', '-1')]
        return sum(v) if v else -1
    b0 = ok[0] if ok else {}
    rows.append({
        'file': os.path.basename(f),
        'dataset': r[0]['dataset'] if r else '',
        'arm': r[0]['arm'] if r else '',
        'batches_compared': len(ok),
        'batches_differing': diff,
        'stopped': ';'.join(stop),
        'ref_patterns_summed': sum(int(x['cmp_ref_patterns']) for x in ok),
        'ref_patterns_last_batch': ok[-1]['cmp_ref_patterns'] if ok else '',
        'rescans': tot('rescans'),
        'rescans_inserting': tot('rescan_inserts'),
        'promotions': tot('promotions'),
        'backscans': tot('backscans'),
        'init_live_nodes': b0.get('live_nodes', ''),
        'init_stubs': b0.get('stubs', ''),
        'init_min_sup': b0.get('min_sup', ''),
        'status': 'OK' if ok and diff == 0 and not stop and len(ok) == len(r) else 'CHECK',
    })
    if rows[-1]['status'] != 'OK':
        bad += 1
keys = ['dataset', 'arm', 'batches_compared', 'batches_differing', 'stopped', 'ref_patterns_summed',
        'ref_patterns_last_batch', 'rescans', 'rescans_inserting', 'promotions', 'backscans',
        'init_live_nodes', 'init_stubs', 'init_min_sup', 'status', 'file']
with open(os.path.join(d, 'summary.tsv'), 'w', newline='\n') as w:
    w.write('\t'.join(keys) + '\n')
    for x in rows:
        w.write('\t'.join(str(x.get(k, '')) for k in keys) + '\n')
for x in rows:
    print('\t'.join(str(x.get(k, '')) for k in keys[:-1]))
print('runs=%d with_problem=%d' % (len(rows), bad))
sys.exit(1 if bad else 0)

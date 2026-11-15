#!/usr/bin/env python3
"""Checks a finished StreamBench campaign directory against bench/plan.txt.

For every plan line there must be exactly one finished run (a .meta.txt whose command matches
the line, with a 'finished=' record, and its .csv). All runs must share one jar, one build
commit, one clean source tree, one Java runtime and one set of JVM arguments; every dataset and
configuration file must have one SHA-256 across runs. Exit codes 0 (done) and 5 (time or heap
limit) are accepted; limit hits are listed. Exit code 1 if anything else is found.

Usage: tools/check_campaign.py <campaign dir> [<plan file>]
"""
import collections
import glob
import os
import re
import sys

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
camp = sys.argv[1]
plan_file = sys.argv[2] if len(sys.argv) > 2 else os.path.join(root, 'bench', 'plan.txt')

plan = []
for line in open(plan_file, encoding='utf-8'):
    line = line.strip()
    if not line or line.startswith('#'):
        continue
    ph, cfg, arm, mode, tag, opts = (line.split(';') + [''] * 6)[:6]
    plan.append((ph, (cfg, arm, mode, tag, ' '.join(opts.split()))))

problems = []
runs = {}
for m in sorted(glob.glob(os.path.join(camp, '*.meta.txt'))):
    kv = {}
    for ln in open(m, encoding='utf-8'):
        ln = ln.rstrip('\r\n')
        if '=' in ln and not ln.startswith(' '):
            k, v = ln.split('=', 1)
            kv.setdefault(k, v)
    cmd = kv.get('command', '')
    a = dict(re.findall(r'--([\w-]+)=(\S+)', cmd))
    known = ('config', 'arm', 'mode', 'tag', 'out', 'lock')
    opts = ' '.join(t for t in cmd.split()[1:] if t.startswith('--') and t[2:].split('=')[0] not in known)
    key = (a.get('config', ''), a.get('arm', ''), a.get('mode', ''), a.get('tag', ''), opts)
    if key in runs:
        problems.append('two runs for one plan line: %s and %s' % (os.path.basename(runs[key]['_file']), os.path.basename(m)))
    kv['_file'] = m
    runs[key] = kv

shared = collections.defaultdict(set)
per_file = collections.defaultdict(set)
limits = []
matched = 0
for ph, key in plan:
    kv = runs.get(key)
    if kv is None:
        problems.append('no run for plan line %s %s' % (ph, ';'.join(key)))
        continue
    matched += 1
    m = kv['_file']
    csv = m[:-len('.meta.txt')] + '.csv'
    fin = kv.get('finished', '')
    ex = re.search(r'exit=(\d+)', fin)
    if not fin or not ex:
        problems.append('unfinished run %s' % os.path.basename(m))
        continue
    if not os.path.exists(csv):
        problems.append('missing result file %s' % os.path.basename(csv))
    code = ex.group(1)
    if code == '5':
        rows = open(csv, encoding='utf-8').read().splitlines() if os.path.exists(csv) else []
        last = rows[-1].split(',') if rows else []
        limits.append('%s (stopped at %s)' % (os.path.basename(csv), ','.join(last[4:6]) + ' ' + (last[-1] if last else '')))
    elif code != '0':
        problems.append('exit code %s in %s' % (code, os.path.basename(m)))
    for k in ('jar_sha256', 'build.source_commit', 'build.source_tree', 'java'):
        shared[k].add(kv.get(k, ''))
    per_file['dataset ' + kv.get('dataset', '')].add(kv.get('dataset_sha256', ''))
    per_file['config ' + kv.get('config_file', '')].add(kv.get('config_sha256', ''))

planned = {k for _, k in plan}
for key, kv in runs.items():
    if key not in planned:
        problems.append('run not in the plan: %s' % os.path.basename(kv['_file']))
for k, vals in shared.items():
    if len(vals) != 1:
        problems.append('%s differs across runs: %s' % (k, sorted(vals)))
if shared.get('build.source_tree') and shared['build.source_tree'] != {'clean'}:
    problems.append('jar built from a modified source tree')
for k, vals in per_file.items():
    if len(vals) != 1:
        problems.append('%s has %d different SHA-256 values' % (k, len(vals)))

print('plan lines=%d runs found=%d matched=%d' % (len(plan), len(runs), matched))
for k in sorted(shared):
    print('%s: %s' % (k, ' | '.join(sorted(shared[k]))))
print('datasets=%d configs=%d' % (sum(1 for k in per_file if k.startswith('dataset')), sum(1 for k in per_file if k.startswith('config'))))
print('limit hits=%d' % len(limits))
for x in limits:
    print('  ' + x)
print('problems=%d' % len(problems))
for x in problems:
    print('  ' + x)
sys.exit(1 if problems else 0)

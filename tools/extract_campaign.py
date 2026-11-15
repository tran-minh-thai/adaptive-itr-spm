#!/usr/bin/env python3
"""Reduces a finished StreamBench campaign to per-run and per-batch summary tables.

Reads every <run>.csv / <run>.meta.txt pair in the campaign directory and writes, into the
output directory:

  runs.tsv     one row per run (phase, dataset, arm, tag, mode, status and the summaries below)
  batches.tsv  one row per (run, batch) for time-mode runs: median of time_ms over the measured
               repetitions, and the counters of the first measured repetition
  extract_meta.txt  command, SHA-256 of this tool and of every input file

Definitions (fixed here, used by every table and test):
  * measured repetitions: rep >= 1; warm-up streams (rep < 0) are never used.
  * init_ms: time_ms of batch 0 (the initial mine of D_init); update_ms: sum of time_ms over
    batches 1..K; total_ms = init_ms + update_ms. Each is computed per repetition, and the
    table reports the median over repetitions (and min and max for update_ms and total_ms).
  * a run that hit the time or heap limit has status TIMEOUT or OOM, the batch it stopped at
    (stop_batch) and the repetition (stop_rep, negative = in the warm-up stream); it has no
    timing summaries when no measured repetition finished. stop_init_ms is the time of batch 0 in
    the stopped stream: the stream stops when init plus update time exceeds the limit, so the update
    time of such a run is more than (limit - stop_init_ms).
  * live mode: peak_live_mb = max live_mb over all batches; peak_live_upd_mb = max over batches
    1..K; final_retained_mb = retained_mb after the last batch; max_retained_mb = max retained_mb.
  * counters (patterns, rescans, rescan_inserts, promotions, tree sizes) come from the first
    measured repetition (time mode) or the single stream (live mode); they are deterministic
    given the code, data and configuration (checked by 'counters_equal_across_reps').

Usage: tools/extract_campaign.py <campaign dir> <output dir>
"""
import csv
import glob
import hashlib
import os
import re
import statistics
import sys

camp, outdir = sys.argv[1], sys.argv[2]
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.makedirs(outdir, exist_ok=True)


def sha(p):
    h = hashlib.sha256()
    with open(p, 'rb') as f:
        for b in iter(lambda: f.read(1 << 20), b''):
            h.update(b)
    return h.hexdigest()


plan_phase = {}
for line in open(os.path.join(root, 'bench', 'plan.txt'), encoding='utf-8'):
    line = line.strip()
    if line and not line.startswith('#'):
        ph, cfg, arm, mode, tag = line.split(';')[:5]
        plan_phase[(cfg, arm, mode, tag)] = ph


def num(x):
    return float(x) if x not in ('', None) else None


COUNTERS = ['patterns', 'rescans', 'rescan_inserts', 'promotions', 'projections', 'live_nodes',
            'tombstones', 'stubs', 'f_size', 'backscans']
runs, batches, inputs = [], [], []
for meta in sorted(glob.glob(os.path.join(camp, '*.meta.txt'))):
    csvf = meta[:-len('.meta.txt')] + '.csv'
    inputs += [meta, csvf]
    kv = {}
    for ln in open(meta, encoding='utf-8'):
        ln = ln.rstrip('\r\n')
        if '=' in ln:
            k, v = ln.split('=', 1)
            kv.setdefault(k, v)
    a = dict(re.findall(r'--([\w-]+)=(\S+)', kv['command']))
    rows = list(csv.DictReader(open(csvf, encoding='utf-8', newline='')))
    stop = [r for r in rows if r['status'] in ('TIMEOUT', 'OOM')]
    ok = [r for r in rows if r['status'] == 'OK']
    bad = [r for r in rows if r['status'] not in ('OK', 'TIMEOUT', 'OOM')]
    if bad:
        sys.exit('unexpected status %s in %s' % (bad[0]['status'], csvf))
    mode = a['mode']
    rec = {
        'phase': plan_phase.get((a['config'], a['arm'], mode, a.get('tag', '-')), '?'),
        'dataset': rows[0]['dataset'], 'arm': a['arm'], 'tag': a.get('tag', '-'), 'mode': mode,
        'file': os.path.basename(csvf),
        'status': stop[0]['status'] if stop else 'OK',
        'stop_rep': stop[0]['rep'] if stop else '',
        'stop_batch': stop[0]['batch'] if stop else '',
        'min_sup_init': '', 'batches': '',
    }
    if mode == 'time':
        reps = sorted({int(r['rep']) for r in ok if int(r['rep']) >= 1})
        full = []
        K = max(int(r['batch']) for r in ok) if ok else 0
        for rp in reps:
            rr = [r for r in ok if int(r['rep']) == rp]
            if len(rr) == K + 1:
                full.append(rp)
        rec['reps_measured'] = len(full)
        rec['batches'] = K
        per = {}
        for rp in full:
            rr = {int(r['batch']): r for r in ok if int(r['rep']) == rp}
            init = num(rr[0]['time_ms'])
            upd = sum(num(rr[b]['time_ms']) for b in range(1, K + 1))
            per[rp] = (init, upd, init + upd)
        if full:
            ins, ups, tots = zip(*per.values())
            rec.update(init_ms_med=statistics.median(ins), update_ms_med=statistics.median(ups),
                       update_ms_min=min(ups), update_ms_max=max(ups),
                       total_ms_med=statistics.median(tots), total_ms_min=min(tots), total_ms_max=max(tots))
            first = {int(r['batch']): r for r in ok if int(r['rep']) == full[0]}
            same = all(all(r[c] == first[int(r['batch'])][c] for c in COUNTERS)
                       for r in ok if int(r['rep']) in full)
            rec['counters_equal_across_reps'] = 'yes' if same else 'NO'
            for b in range(0, K + 1):
                ts = [num(r['time_ms']) for r in ok if int(r['rep']) in full and int(r['batch']) == b]
                row = {'dataset': rec['dataset'], 'arm': rec['arm'], 'tag': rec['tag'], 'batch': b,
                       'n_total': first[b]['n_total'], 'min_sup': first[b]['min_sup'],
                       'time_ms_med': statistics.median(ts), 'time_ms_min': min(ts), 'time_ms_max': max(ts)}
                row.update({c: first[b][c] for c in COUNTERS})
                batches.append(row)
            src = first
        else:
            src = None
            if stop:
                rec['stop_cum_ms'] = num(stop[0]['cum_ms']) if stop[0]['cum_ms'] else ''
                prev = [r for r in rows if r['rep'] == stop[0]['rep'] and r['status'] == 'OK']
                if prev:
                    rec['stop_cum_ms'] = num(prev[-1]['cum_ms'])
                    b0 = [r for r in prev if r['batch'] == '0']
                    rec['stop_init_ms'] = num(b0[0]['time_ms']) if b0 else ''
    else:
        rr = {int(r['batch']): r for r in ok}
        rec['reps_measured'] = 1 if not stop else 0
        rec['batches'] = max(rr) if rr else 0
        if rr:
            lv = [num(rr[b]['live_mb']) for b in sorted(rr)]
            rt = [num(rr[b]['retained_mb']) for b in sorted(rr)]
            rec.update(peak_live_mb=max(lv), peak_live_upd_mb=max(lv[1:]) if len(lv) > 1 else '',
                       final_retained_mb=rt[-1], max_retained_mb=max(rt),
                       gc_forced=sum(int(rr[b]['gc_forced']) for b in rr))
        src = rr if not stop else None
    if src:
        Kx = max(src)
        rec['min_sup_init'] = src[0]['min_sup']
        rec['final_patterns'] = src[Kx]['patterns']
        for c in ('rescans', 'rescan_inserts', 'promotions', 'backscans'):
            v = [int(src[b][c]) for b in range(1, Kx + 1) if src[b][c] not in ('', '-1')]
            rec[c] = sum(v) if v else ''
        for c in ('live_nodes', 'tombstones', 'stubs'):
            v = src[Kx][c]
            rec['final_' + c] = v if v not in ('', '-1') else ''
            vv = [int(src[b][c]) for b in src if src[b][c] not in ('', '-1')]
            rec['max_' + c] = max(vv) if vv else ''
    runs.append(rec)

RUN_KEYS = ['phase', 'dataset', 'arm', 'tag', 'mode', 'status', 'stop_rep', 'stop_batch', 'stop_cum_ms', 'stop_init_ms',
            'reps_measured', 'batches', 'min_sup_init', 'init_ms_med', 'update_ms_med', 'update_ms_min',
            'update_ms_max', 'total_ms_med', 'total_ms_min', 'total_ms_max', 'peak_live_mb',
            'peak_live_upd_mb', 'final_retained_mb', 'max_retained_mb', 'gc_forced', 'final_patterns',
            'rescans', 'rescan_inserts', 'promotions', 'backscans', 'final_live_nodes', 'max_live_nodes',
            'final_tombstones', 'max_tombstones', 'final_stubs', 'max_stubs', 'counters_equal_across_reps', 'file']
BATCH_KEYS = ['dataset', 'arm', 'tag', 'batch', 'n_total', 'min_sup', 'time_ms_med', 'time_ms_min',
              'time_ms_max'] + COUNTERS


def write(name, keys, data):
    with open(os.path.join(outdir, name), 'w', newline='\n', encoding='utf-8') as w:
        w.write('\t'.join(keys) + '\n')
        for r in data:
            w.write('\t'.join('' if r.get(k) is None else (('%.3f' % r[k]) if isinstance(r.get(k), float) else str(r.get(k, ''))) for k in keys) + '\n')


write('runs.tsv', RUN_KEYS, runs)
write('batches.tsv', BATCH_KEYS, batches)
with open(os.path.join(outdir, 'extract_meta.txt'), 'w', newline='\n', encoding='utf-8') as w:
    w.write('command=tools/extract_campaign.py %s %s\n' % (os.path.relpath(camp, root).replace(os.sep, '/'),
                                                         os.path.relpath(outdir, root).replace(os.sep, '/')))
    w.write('tool_sha256=%s\n' % sha(os.path.abspath(__file__)))
    w.write('plan_sha256=%s\n' % sha(os.path.join(root, 'bench', 'plan.txt')))
    w.write('runs=%d batch_rows=%d\n' % (len(runs), len(batches)))
    for p in inputs:
        w.write('input %s %s\n' % (sha(p), os.path.basename(p)))
unk = [r['file'] for r in runs if r['phase'] == '?']
noteq = [r['file'] for r in runs if r.get('counters_equal_across_reps') == 'NO']
print('runs=%d batch_rows=%d unknown_phase=%d counters_differ=%d' % (len(runs), len(batches), len(unk), len(noteq)))
for x in unk + noteq:
    print('  ' + x)
sys.exit(1 if unk or noteq else 0)

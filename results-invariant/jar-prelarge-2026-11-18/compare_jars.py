#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Compares the measured jar of the main campaign with the jar of phases prelarge and sigma.

Per class file of the old jar: identical bytes, or else identical code (javap -c -p -s -constants,
constant-pool indices and spacing masked, so line-number tables and constant-pool order are ignored).
For classes whose code differs, the methods are compared one by one and the differing instructions
are printed. Usage (repository root): python3 <this file> <old jar> <new jar>
"""
import difflib, hashlib, os, re, subprocess, sys, tempfile, zipfile


def entries(p):
    z = zipfile.ZipFile(p)
    return z, {n: hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if not n.endswith('/')}


def methods(path):
    t = subprocess.run(['javap', '-c', '-p', '-s', '-constants', path], capture_output=True, text=True, check=True).stdout
    blocks, cur, buf = {}, None, []
    for ln in t.splitlines():
        if re.match(r'^  \S.*\(.*\).*;$|^  static \{\};$', ln):
            if cur:
                blocks[cur] = buf
            cur, buf = ln.strip(), []
        elif cur:
            buf.append(' '.join(re.sub(r'#\d+', '#', ln).split()))
    if cur:
        blocks[cur] = buf
    return blocks


def norm(lines):
    out = []
    for l in lines:
        l = re.sub(r'^\d+: ', '', l)
        l = l.replace('ldc_w ', 'ldc ').replace('ldc2_w ', 'ldc2 ')
        l = re.sub(r'^(goto|if\w*|jsr)( \d+)$', r'\1 _', l)
        l = re.sub(r'^-?\d+: \d+$', '_: _', l)
        l = re.sub(r'^default: \d+$', 'default: _', l)
        l = re.sub(r'^\d+ \d+ \d+ ', '_ _ _ ', l)
        out.append(l)
    return out


za, a = entries(sys.argv[1])
zb, b = entries(sys.argv[2])
print('old entries %d, new entries %d' % (len(a), len(b)))
print('only in new: %s' % sorted(set(b) - set(a)))
print('only in old: %s' % sorted(set(a) - set(b)))
classes = sorted(n for n in a if n.endswith('.class'))
same_bytes = same_code = 0
with tempfile.TemporaryDirectory() as d:
    for n in classes:
        if n not in b:
            print('MISSING in new: %s' % n)
            continue
        if a[n] == b[n]:
            same_bytes += 1
            same_code += 1
            continue
        pa, pb = os.path.join(d, 'a.class'), os.path.join(d, 'b.class')
        open(pa, 'wb').write(za.read(n))
        open(pb, 'wb').write(zb.read(n))
        ma, mb = methods(pa), methods(pb)
        changed = [k for k in ma if k in mb and ma[k] != mb[k]]
        gone = [k for k in ma if k not in mb]
        added = [k for k in mb if k not in ma]
        # a larger constant pool turns ldc into ldc_w and shifts every later offset; compare the
        # instruction sequence with offsets, branch targets and exception-table ranges masked
        loose = [k for k in changed if norm(ma[k]) != norm(mb[k])]
        for k in changed:
            if k not in loose:
                print('  %s: same instructions up to ldc / ldc_w and offsets (%s)' % (n, k))
        changed = loose
        if not changed and not gone and not added:
            same_code += 1
            continue
        print('CODE DIFFERS: %s (methods: old %d, new %d, identical %d)' % (n, len(ma), len(mb), sum(1 for k in ma if k in mb and ma[k] == mb[k])))
        for k in added:
            print('  added   %s' % k)
        for k in gone:
            print('  removed %s' % k)
        for k in changed:
            ops = [l for l in difflib.unified_diff(ma[k], mb[k], lineterm='', n=0) if l[:1] in '+-' and l[:3] not in ('+++', '---')]
            print('  changed %s: %d lines differ' % (k, len(ops)))
print('class files of the old jar: %d; identical bytes: %d; identical code (offsets and ldc / ldc_w masked): %d' % (len(classes), same_bytes, same_code))

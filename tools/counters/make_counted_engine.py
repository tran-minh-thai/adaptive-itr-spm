#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Writes IncAdaTaSPMCounted.java, a copy of the measured engine with extra counters.

The copy is derived mechanically from src/.../IncAdaTaSPM.java: every edit is a textual
replacement whose old text must occur the stated number of times, so the copy cannot drift from
the measured source. The measured jar is not changed. Added counters:
  projBy[phase]    projections by source: 0 initial mine, 1 batch walk (on the increment),
                   2 rescan expansion, 3 stub promotion, 4 passing through an ancestor on the way
                   to a marked node (all of 2 to 4 are projections on the full D_T);
  rescPerNode[id]  rescans of a tracked node since it was created; maxRescOneNode their maximum.
Usage: make_counted_engine.py <repository root> <output directory>
"""
import re, sys, os, hashlib

root, out = sys.argv[1], sys.argv[2]
src_path = os.path.join(root, 'src/main/java/com/adaptive/itr/spm/IncAdaTaSPM.java')
src = open(src_path, encoding='utf-8').read()

EDITS = [
    ('public long rescanInserts = 0;', 1,
     'public long rescanInserts = 0;\n'
     '        /** Counted copy: projections by source and rescans per tracked node. */\n'
     '        public int phase = 0;\n'
     '        public final long[] projBy = new long[5];\n'
     '        public int[] rescPerNode = new int[1024];\n'
     '        public int maxRescOneNode = 0;\n'
     '        void resetResc(int c) { if (c >= rescPerNode.length) rescPerNode = Arrays.copyOf(rescPerNode, Math.max(c + 1, rescPerNode.length * 2)); rescPerNode[c] = 0; }'),
    ('projections++;', 2, 'projections++; projBy[phase]++;'),
    ('int c = t.alloc(parent, key(item, sExt), (t.fl[parent] & Tree.HAST) != 0 || isT[item]);', 1,
     'int c = t.alloc(parent, key(item, sExt), (t.fl[parent] & Tree.HAST) != 0 || isT[item]); resetResc(c);'),
    ('t.fl[P] &= ~(Tree.RESC | Tree.PROMO); rescans++;', 1,
     't.fl[P] &= ~(Tree.RESC | Tree.PROMO); rescans++;\n'
     '                if (P >= rescPerNode.length) resetResc(P);\n'
     '                if (++rescPerNode[P] > maxRescOneNode) maxRescOneNode = rescPerNode[P];'),
    ('                expand(P, pp, d);\n                if ((t.kid[P] == null ? 0 : t.kid[P].length) > before) rescanInserts++;', 1,
     '                { int sv = phase; phase = 2; expand(P, pp, d); phase = sv; }\n                if ((t.kid[P] == null ? 0 : t.kid[P].length) > before) rescanInserts++;'),
    ('else if ((t.fl[P] & Tree.PROMO) != 0) { t.fl[P] &= ~Tree.PROMO; promote(P, pp, d); }', 1,
     'else if ((t.fl[P] & Tree.PROMO) != 0) { t.fl[P] &= ~Tree.PROMO; int sv = phase; phase = 3; promote(P, pp, d); phase = sv; }'),
    ('                Proj cp = (P == ROOT) ? itemProj(item, 0) : extend(pp, item, sx, false);\n                rescan(c, cp, d + 1);', 1,
     '                int sv = phase; phase = 4;\n                Proj cp = (P == ROOT) ? itemProj(item, 0) : extend(pp, item, sx, false);\n                phase = sv;\n                rescan(c, cp, d + 1);'),
    ('            boolean m = walk(ROOT, null);', 1, '            phase = 1;\n            boolean m = walk(ROOT, null);\n            phase = 0;'),
]
for old, times, new in EDITS:
    k = src.count(old)
    if k != times:
        sys.exit('edit not applicable: %r occurs %d times, expected %d' % (old[:60], k, times))
    src = src.replace(old, new)
src = re.sub(r'\bIncAdaTaSPM\b', 'IncAdaTaSPMCounted', src)
os.makedirs(out, exist_ok=True)
dst = os.path.join(out, 'IncAdaTaSPMCounted.java')
open(dst, 'w', encoding='utf-8').write(src)
print('source sha256', hashlib.sha256(open(src_path, 'rb').read()).hexdigest())
print('copy   sha256', hashlib.sha256(src.encode('utf-8')).hexdigest(), '->', dst)

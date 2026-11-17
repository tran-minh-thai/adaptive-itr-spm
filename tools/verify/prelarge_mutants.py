#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Fault injection for PreLargeRandomCheck.

Each mutant is one textual replacement in a copy of PreLargeSeqTaSPM.java (the replaced text
must occur exactly once). The copy is compiled with the check into a temporary directory and the
check is run; a fault mutant must make the check fail (exit code 1) and a benign mutant must keep
it passing. Exit code 1 when any mutant behaves otherwise.

Usage: tools/verify/prelarge_mutants.py [cases] [seed]   (run from the repository root after
mvn package, so that target/classes exists)
"""
import os, subprocess, sys, tempfile

SRC = 'src/main/java/com/adaptive/itr/spm/PreLargeSeqTaSPM.java'
CHECKS = ['tools/verify/java/BatchRandomCheck.java', 'tools/verify/java/PreLargeRandomCheck.java']

# (name, old text, new text, must the check fail?, regularity pruning of the run)
MUTANTS = [
    ('control-unchanged', None, None, False, 'false'),
    ('control-unchanged-regprune', None, None, False, 'true'),
    ('buf-not-accumulated', 'buf += q;', 'buf += 0;', True, 'false'),
    ('swumax-zero', 'long q = swuMax(all, from);', 'long q = 0;', True, 'false'),
    ('f-from-doubled-n', 'nAtRescan = n;', 'nAtRescan = 2 * n;', True, 'false'),
    ('support-not-updated', 'sup[c] += l.size();', 'sup[c] += 0;', True, 'false'),
    ('last-position-not-updated', 'last[c] = ln;', '', True, 'false'),
    ('benign-never-drop', 'if (sup[c] < low || (pruneIrregular && irregular(c))) {', 'if (false) {', False, 'false'),
    ('regprune-gap-off-by-one', 'return Math.max(gap[v], nNow - last[v]) > maxPer;', 'return Math.max(gap[v], nNow - last[v]) >= maxPer;', True, 'true'),
    ('regprune-ignores-tail-gap', 'return Math.max(gap[v], nNow - last[v]) > maxPer;', 'return Math.max(gap[v], 0L) > maxPer;', False, 'true'),
]


def run(name, old, new, cases, seed, regprune):
    src = open(SRC, encoding='utf-8').read()
    if old is not None:
        k = src.count(old)
        if k != 1:
            return name, 'replacement occurs %d times' % k, None
        mutated = src.replace(old, new)
        assert mutated != src
        src = mutated
    with tempfile.TemporaryDirectory() as d:
        os.makedirs(os.path.join(d, 'src'))
        f = os.path.join(d, 'src', 'PreLargeSeqTaSPM.java')
        open(f, 'w', encoding='utf-8').write(src)
        out = os.path.join(d, 'classes')
        os.makedirs(out)
        cp = 'target/classes'
        r = subprocess.run(['javac', '-d', out, '-cp', cp, f] + CHECKS, capture_output=True, text=True)
        if r.returncode != 0:
            return name, 'compile error: ' + r.stderr.strip()[:300], None
        r = subprocess.run(['java', '-cp', out + os.pathsep + cp, 'com.adaptive.itr.spm.PreLargeRandomCheck', str(cases), str(seed), '0.5', regprune],
                           capture_output=True, text=True)
        last = [l for l in r.stdout.splitlines() if l.startswith('PreLargeSeq:') or l.startswith('cases=')]
        return name, r.returncode, ' | '.join(last)


def main():
    cases = int(sys.argv[1]) if len(sys.argv) > 1 else 2000
    seed = int(sys.argv[2]) if len(sys.argv) > 2 else 20261117
    wrong = 0
    for name, old, new, must_fail, regprune in MUTANTS:
        n, code, info = run(name, old, new, cases, seed, regprune)
        ok = (code == 1) if must_fail else (code == 0)
        if not isinstance(code, int):
            ok = False
        wrong += 0 if ok else 1
        print('%-28s expected=%-4s exit=%s %s :: %s' % (n, 'fail' if must_fail else 'pass', code, 'OK' if ok else 'WRONG', info))
    print('mutants=%d behaving_as_expected=%d' % (len(MUTANTS), len(MUTANTS) - wrong))
    sys.exit(1 if wrong else 0)


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Downloads the dataset files of the experiments into datasets/ and checks each against its SHA-256.

The files come from the SPMF public dataset collection and are not redistributed in this
repository. datasets/SOURCES.tsv lists, for every file, its source, a transform and the SHA-256
of the file that every measured run read:

  none                    the downloaded file as served
  crlf-to-lf              the downloaded file with CRLF line ends replaced by LF
  prefix:<file>:<percent> the first floor(lines * percent / 100) lines of another file of the list

A file whose SHA-256 differs from the list is not kept (it is written under a temporary name and
removed), and the tool exits with code 1.

Usage:
  python tools/fetch_datasets.py               fetch what is missing or wrong
  python tools/fetch_datasets.py --verify-only check what is there, download nothing
  python tools/fetch_datasets.py --force       fetch everything again
  python tools/fetch_datasets.py --self-test   check the tool on small local files (no network)

Standard library only; runs on Windows, macOS and Linux.
"""
import argparse
import hashlib
import os
import shutil
import sys
import tempfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DIR = ROOT / 'datasets'


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for block in iter(lambda: f.read(1 << 20), b''):
            h.update(block)
    return h.hexdigest()


def read_manifest(path):
    rows = []
    for line in Path(path).read_text(encoding='utf-8').splitlines():
        if not line.strip() or line.startswith('#') or line.startswith('name\t'):
            continue
        name, source, transform, digest = line.split('\t')
        rows.append({'name': name, 'source': source, 'transform': transform, 'sha256': digest.lower()})
    return rows


def lines_of(data):
    parts = data.split(b'\n')
    if parts and parts[-1] == b'':
        parts = parts[:-1]
    return parts


def produce(row, directory):
    """Returns the bytes of the file described by row."""
    tr = row['transform']
    if tr.startswith('prefix:'):
        _, base, pct = tr.split(':')
        parts = lines_of((Path(directory) / base).read_bytes())
        keep = len(parts) * int(pct) // 100
        return b'\n'.join(parts[:keep]) + b'\n'
    # the SPMF server answers 403 to the default Python client; a plain browser-style client name is accepted
    req = urllib.request.Request(row['source'], headers={'User-Agent': 'Mozilla/5.0 (compatible; dataset-fetch)'})
    with urllib.request.urlopen(req, timeout=600) as r:
        data = r.read()
    if tr == 'crlf-to-lf':
        return data.replace(b'\r\n', b'\n')
    if tr == 'none':
        return data
    raise ValueError('unknown transform %r for %s' % (tr, row['name']))


def run(directory, manifest, verify_only=False, force=False, out=print):
    """Returns (ok, wrong, missing, fetched) counts over the files of the manifest."""
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    rows = read_manifest(manifest)
    ok = wrong = missing = fetched = 0
    # files that others are cut from come first, so a prefix always reads a checked base file
    rows.sort(key=lambda r: r['transform'].startswith('prefix:'))
    for row in rows:
        target = directory / row['name']
        present = target.is_file()
        if present and not force and sha256_of(target) == row['sha256']:
            ok += 1
            out('ok       %s' % row['name'])
            continue
        if verify_only:
            if present:
                wrong += 1
                out('WRONG    %s: SHA-256 %s, expected %s' % (row['name'], sha256_of(target), row['sha256']))
            else:
                missing += 1
                out('MISSING  %s (expected at %s)' % (row['name'], target))
            continue
        tmp = target.with_name(target.name + '.part')
        try:
            data = produce(row, directory)
        except Exception as e:  # network or file error: reported, the run continues with the next file
            wrong += 1
            out('FAILED   %s: %s' % (row['name'], e))
            continue
        tmp.write_bytes(data)
        got = sha256_of(tmp)
        if got != row['sha256']:
            tmp.unlink()
            wrong += 1
            out('WRONG    %s: SHA-256 %s from %s, expected %s; not kept' % (row['name'], got, row['source'], row['sha256']))
            continue
        os.replace(tmp, target)
        fetched += 1
        out('fetched  %s' % row['name'])
    out('files=%d ok=%d fetched=%d wrong=%d missing=%d' % (len(rows), ok, fetched, wrong, missing))
    return ok, wrong, missing, fetched


def self_test():
    """Small local sources served as file:// URLs; every check must catch its fault."""
    fails = 0
    work = Path(tempfile.mkdtemp(prefix='fetch-selftest-'))
    try:
        src = work / 'src'
        src.mkdir()
        plain = b'1 -1 2 -1 -2\n3 -1 -2\n'
        crlf = b'1 -1 -2\r\n2 -1 -2\r\n3 -1 -2\r\n4 -1 -2\r\n5 -1 -2\r\n'
        (src / 'plain.txt').write_bytes(plain)
        (src / 'crlf.txt').write_bytes(crlf)
        lf = crlf.replace(b'\r\n', b'\n')
        prefix = b'\n'.join(lines_of(lf)[:5 * 40 // 100]) + b'\n'
        h = lambda b: hashlib.sha256(b).hexdigest()

        def manifest(rows):
            p = work / 'SOURCES.tsv'
            p.write_text('name\tsource\ttransform\tsha256\n' + ''.join('\t'.join(r) + '\n' for r in rows), encoding='utf-8')
            return p

        good = [['A.txt', (src / 'plain.txt').as_uri(), 'none', h(plain)],
                ['K.txt', (src / 'crlf.txt').as_uri(), 'crlf-to-lf', h(lf)],
                ['K_40.txt', 'K.txt', 'prefix:K.txt:40', h(prefix)]]
        quiet = lambda s: None

        def check(name, cond):
            nonlocal fails
            print('%-58s %s' % (name, 'ok' if cond else 'FAIL'))
            fails += not cond

        d = work / 'd1'
        r = run(d, manifest(good), out=quiet)
        check('fetch: 3 files fetched, none wrong', r == (0, 0, 0, 3))
        check('crlf-to-lf output has no CR byte', b'\r' not in (d / 'K.txt').read_bytes())
        check('prefix holds floor(5 * 40 / 100) = 2 lines', len(lines_of((d / 'K_40.txt').read_bytes())) == 2)
        r = run(d, manifest(good), out=quiet)
        check('second run: 3 ok, nothing fetched', r == (3, 0, 0, 0))
        r = run(d, manifest(good), verify_only=True, out=quiet)
        check('verify-only on correct files: 3 ok', r == (3, 0, 0, 0))
        # fault 1: a local file is changed after the fetch
        (d / 'A.txt').write_bytes(plain + b'9 -1 -2\n')
        r = run(d, manifest(good), verify_only=True, out=quiet)
        check('fault: changed file is reported wrong', r[1] == 1)
        # fault 2: a local file is deleted
        (d / 'K_40.txt').unlink()
        r = run(d, manifest(good), verify_only=True, out=quiet)
        check('fault: deleted file is reported missing', r[2] == 1)
        # fault 3: the source serves other bytes than the pinned ones
        bad = [list(x) for x in good]
        bad[0][3] = h(b'other')
        d2 = work / 'd2'
        r = run(d2, manifest(bad), out=quiet)
        check('fault: source with other bytes is rejected', r[1] == 1)
        check('fault: rejected file is not kept, no .part left', not (d2 / 'A.txt').exists() and not (d2 / 'A.txt.part').exists())
        # fault 4: the CRLF transform is skipped
        bad = [list(x) for x in good]
        bad[1][2] = 'none'
        r = run(work / 'd3', manifest(bad), out=quiet)
        check('fault: missing line-end conversion is rejected', r[1] >= 1)
        # fault 5: unreachable source
        bad = [list(x) for x in good]
        bad[0][1] = (src / 'absent.txt').as_uri()
        r = run(work / 'd4', manifest(bad), out=quiet)
        check('fault: unreachable source is reported, not crashed', r[1] == 1)
    finally:
        shutil.rmtree(work, ignore_errors=True)
    print('self-test failures=%d' % fails)
    return 1 if fails else 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--dir', default=str(DEFAULT_DIR))
    ap.add_argument('--manifest', default=str(DEFAULT_DIR / 'SOURCES.tsv'))
    ap.add_argument('--verify-only', action='store_true')
    ap.add_argument('--force', action='store_true')
    ap.add_argument('--self-test', action='store_true')
    a = ap.parse_args()
    if a.self_test:
        sys.exit(self_test())
    ok, wrong, missing, fetched = run(a.dir, a.manifest, verify_only=a.verify_only, force=a.force)
    sys.exit(1 if wrong or missing else 0)


if __name__ == '__main__':
    main()

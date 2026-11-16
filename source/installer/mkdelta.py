#!/usr/bin/env python3
"""Builds the installer payload: binary deltas (original entry -> BTA-Movil entry) and new files.
Usage: mkdelta.py <orig dir> <final dir> <orig bta.jar> <out res dir>
Delta format: ops until 0x00. 0x01 COPY varint(off) varint(len) | 0x02 ADD varint(len) bytes."""
import os, sys, hashlib

B = 12
def varint(n):
    o = bytearray()
    while True:
        b = n & 0x7f; n >>= 7
        if n: o.append(b | 0x80)
        else: o.append(b); return bytes(o)

def delta(src, dst):
    idx = {}
    for i in range(len(src) - B + 1):
        idx.setdefault(src[i:i+B], []).append(i)
    out = bytearray(); lit = bytearray(); i = 0; n = len(dst)
    def flush():
        if lit: out.extend(b'\x02' + varint(len(lit)) + lit); lit.clear()
    while i < n:
        best_len = 0; best_off = 0
        if i + B <= n:
            for off in idx.get(dst[i:i+B], ())[-16:]:
                l = B
                while off + l < len(src) and i + l < n and src[off+l] == dst[i+l]: l += 1
                if l > best_len: best_len, best_off = l, off
        if best_len >= B:
            flush(); out.extend(b'\x01' + varint(best_off) + varint(best_len)); i += best_len
        else:
            lit.append(dst[i]); i += 1
    flush(); out.append(0)
    return bytes(out)

def apply(src, d):
    o = bytearray(); p = 0
    def rv():
        nonlocal p
        s = 0; sh = 0
        while True:
            b = d[p]; p += 1; s |= (b & 0x7f) << sh; sh += 7
            if not b & 0x80: return s
    while True:
        op = d[p]; p += 1
        if op == 0: return bytes(o)
        if op == 1: off = rv(); l = rv(); o += src[off:off+l]
        else: l = rv(); o += d[p:p+l]; p += l

def walk(r):
    m = {}
    for d, _, fs in os.walk(r):
        for f in fs:
            p = os.path.join(d, f); m[os.path.relpath(p, r).replace(os.sep, '/')] = p
    return m

orig, final, jar, res = sys.argv[1:5]
os.makedirs(res + '/p', exist_ok=True)
a, b = walk(orig), walk(final)
sha = lambda x: hashlib.sha1(x).hexdigest()
lines = ['SRC\t' + sha(open(jar, 'rb').read())]
k = 0; tot = 0
for name in sorted(b):
    nb = open(b[name], 'rb').read()
    if name in a:
        ob = open(a[name], 'rb').read()
        if ob == nb: continue
        d = delta(ob, nb); assert apply(ob, d) == nb, name
        lines.append('D\t%s\t%s\t%s\t%d' % (name, sha(ob), sha(nb), k))
        data = d
    else:
        lines.append('A\t%s\t-\t%s\t%d' % (name, sha(nb), k))
        data = nb
    open('%s/p/%d' % (res, k), 'wb').write(data); tot += len(data); k += 1
assert not [x for x in a if x not in b], 'removed entries not supported'
open(res + '/manifest.txt', 'w', newline='\n').write('\n'.join(lines) + '\n')
print('entries', k, 'payload bytes', tot)

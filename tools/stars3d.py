#!/usr/bin/env python3
"""HYG žvaigždžių katalogas -> app/src/main/assets/stars3d.csv (3D žvaigždžių laukui).

Naudojama: python3 tools/stars3d.py hygdata.csv[.gz] išvestis.csv
Imamos žvaigždės iki 6,5 ryškio su žinomu atstumu (HYG x, y, z – pusiaujo J2000 koordinatės parsekais).
Šaltinis: HYG database (astronexus), licencija CC BY-SA 4.0.
"""
import csv, gzip, io, sys

src, dst = sys.argv[1], sys.argv[2]
raw = open(src, 'rb').read()
if raw[:2] == b'\x1f\x8b':
    raw = gzip.decompress(raw)
rows = csv.DictReader(io.StringIO(raw.decode('utf-8', 'replace')))
out = []
for r in rows:
    try:
        mag = float(r['mag']); dist = float(r['dist'])
        if r.get('proper', '') == 'Sol' or dist <= 0 or dist >= 99999 or mag > 6.5:
            continue
        # HYG v3: x, y, z; v4: x0, y0, z0; kitaip – iš RA (val.), Dec (°) ir atstumo
        def g(*names):
            for n in names:
                if r.get(n) not in (None, ''):
                    return float(r[n])
            return None
        x, y, z = g('x', 'x0'), g('y', 'y0'), g('z', 'z0')
        if x is None or y is None or z is None:
            import math
            ra = math.radians(float(r['ra']) * 15.0); de = math.radians(float(r['dec']))
            x, y, z = dist * math.cos(de) * math.cos(ra), dist * math.cos(de) * math.sin(ra), dist * math.sin(de)
        am = float(r['absmag'])
        ci = float(r['ci']) if r.get('ci') not in (None, '') else 0.6
        out.append((x, y, z, am, ci))
    except (KeyError, ValueError):
        continue
with open(dst, 'w') as f:
    f.write('# HYG database (astronexus, CC BY-SA 4.0): x,y,z pc (J2000 pusiaujo), absoliutus ryškis, B-V\n')
    for x, y, z, am, ci in out:
        f.write('%.3f,%.3f,%.3f,%.2f,%.2f\n' % (x, y, z, am, ci))
print('žvaigždžių:', len(out))

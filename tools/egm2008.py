#!/usr/bin/env python3
"""EGM2008 geoido aukščiai (NGA, viešoji sritis) -> programos tinklelis WORLD_EGM2008.txt.
Įėjimas: PROJ CDN GeoTIFF us_nga_egm08_25.tif (2,5' tinklelis). Išėjimas: 0,25° tinklelis,
platuma -90..90, ilguma -180..180 (1441 stulpelis), eilutės „platuma ilguma N“ (m)."""
import sys
import numpy as np
import tifffile

src, dst = sys.argv[1], sys.argv[2]
a = tifffile.imread(src).astype(np.float64)
if a.ndim > 2:
    a = a[..., 0]
rows, cols = a.shape
# PROJ tinkleliai – taškų centrai: eilutė 0 = +90°, stulpelis 0 = -180°
dlat = 180.0 / (rows - 1)
dlon = 360.0 / (cols - 1) if cols % 2 == 1 else 360.0 / cols
print(f"tinklelis {rows}x{cols}, žingsnis {dlat*60:.2f}' x {dlon*60:.2f}', N {a.min():.1f}..{a.max():.1f} m")
with open(dst, "w") as f:
    for i in range(721):
        lat = -90.0 + 0.25 * i
        r = int(round((90.0 - lat) / dlat))
        r = min(max(r, 0), rows - 1)
        for j in range(1441):
            lon = -180.0 + 0.25 * j
            c = int(round((lon + 180.0) / dlon)) % cols
            f.write(f"{lat:.2f} {lon:.2f} {a[r, c]:.3f}\n")
print("įrašyta", dst)

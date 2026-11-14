#!/usr/bin/env python3
"""NASA SVS 4851 starmap_2020_8k.exr -> stars_bg_8k.jpg (8192x4096) programos išdėstymu:
stulpelis = ((180 + RA) mod 360)/360·W, eilutė = (90 − Dec)/180·H (kaip esamas stars_bg.jpg).
EXR išdėstymas nustatomas pagal 13 ryškiausių žvaigždžių koordinates; tonai – pagal esamą 4k foną.
Jei žvaigždės nesutampa – failas nekuriamas (programa naudoja 4k)."""
import sys, numpy as np
from PIL import Image
Image.MAX_IMAGE_PIXELS = None
exr, ref_path, out = sys.argv[1], sys.argv[2], sys.argv[3]
import OpenEXR, Imath
f = OpenEXR.InputFile(exr)
dw = f.header()['dataWindow']; W = dw.max.x - dw.min.x + 1; H = dw.max.y - dw.min.y + 1
pt = Imath.PixelType(Imath.PixelType.FLOAT)
img = np.stack([np.frombuffer(f.channel(c, pt), dtype=np.float32).reshape(H, W) for c in 'RGB'], -1)
img = np.clip(img, 0, None)
print('EXR', W, H, 'max', float(img.max()))
lum = img.mean(-1)
STARS = {'Sirius': (101.287, -16.716), 'Canopus': (95.988, -52.696), 'Arcturus': (213.915, 19.182), 'Vega': (279.234, 38.784),
         'Capella': (79.172, 45.998), 'Rigel': (78.634, -8.202), 'Betelgeuse': (88.793, 7.407), 'Altair': (297.696, 8.868),
         'Deneb': (310.358, 45.280), 'Antares': (247.352, -26.432), 'Spica': (201.298, -11.161), 'Aldebaran': (68.98, 16.509),
         'Polaris': (37.95, 89.26)}
thr = np.quantile(lum, 0.9999)
def score(sgn, vflip, off):
    n = 0
    for ra, de in STARS.values():
        x = int(((off + sgn * ra) % 360) / 360 * W) % W
        y = int(((90 + de) if vflip else (90 - de)) / 180 * H); y = min(max(y, 4), H - 5)
        if lum[y - 4:y + 5, max(x - 4, 0):x + 5].max() >= thr: n += 1
    return n
best = max(((score(s, v, o), s, v, o) for s in (1, -1) for v in (False, True) for o in np.arange(0, 360, 0.25)), key=lambda t: t[0])
print('ryškiausių žvaigždžių sutapo', best[0], 'iš', len(STARS), '; ženklas', best[1], 'apverstas', best[2], 'poslinkis', best[3])
if best[0] < 11: print('per mažai sutapimų – 8k nekuriamas'); sys.exit(0)
_, sgn, vflip, off = best
# perstatymas į programos išdėstymą
xt = np.arange(W); ra_t = (xt + 0.5) / W * 360 - 180
xs = (np.floor(((off + sgn * ra_t) % 360) / 360 * W).astype(int)) % W
img = img[:, xs]
if vflip: img = img[::-1]
ref = np.asarray(Image.open(ref_path).convert('RGB'), dtype=np.float32) / 255.0
rh, rw = ref.shape[:2]; k = W // rw
small = img[:rh * k, :rw * k].reshape(rh, k, rw, k, 3).mean((1, 3))
q = np.linspace(0, 1, 1025)
o = np.empty(img.shape, dtype=np.uint8)
for c in range(3):
    src = np.quantile(small[..., c], q); dst = np.quantile(ref[..., c], q)
    src = np.maximum.accumulate(src + np.arange(len(src)) * 1e-12)
    o[..., c] = np.clip(np.interp(img[..., c], src, dst) * 255 + 0.5, 0, 255).astype(np.uint8)
# patikra: ryškiausios žvaigždės programos išdėstymu
L = o.mean(-1); ok = 0
for ra, de in STARS.values():
    x = int(((180 + ra) % 360) / 360 * W) % W; y = min(max(int((90 - de) / 180 * H), 4), H - 5)
    ok += L[y - 4:y + 5, max(x - 4, 0):x + 5].max() > 200
print('patikra programos išdėstymu:', ok, 'iš', len(STARS))
Image.fromarray(o).save(out, quality=90, optimize=True)
print('įrašyta', out, o.shape)

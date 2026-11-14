#!/usr/bin/env python3
"""NASA SVS 4851 starmap_2020_8k.exr -> app/src/main/assets/stars_bg_8k.jpg (8192x4096),
išlyginta pagal esamą stars_bg.jpg (apvertimai, poslinkis pagal RA, tonai pagal kvantilius).
Jei kas nepavyksta – failas nekuriamas (programa naudoja 4k)."""
import sys, numpy as np
from PIL import Image
Image.MAX_IMAGE_PIXELS = None
exr, ref_path, out = sys.argv[1], sys.argv[2], sys.argv[3]
import OpenEXR, Imath
f = OpenEXR.InputFile(exr)
dw = f.header()['dataWindow']; W = dw.max.x - dw.min.x + 1; H = dw.max.y - dw.min.y + 1
pt = Imath.PixelType(Imath.PixelType.FLOAT)
ch = [np.frombuffer(f.channel(c, pt), dtype=np.float32).reshape(H, W) for c in 'RGB']
img = np.stack(ch, -1); del ch
print('EXR', W, H, 'min', float(img.min()), 'max', float(img.max()))
img = np.clip(img, 0, None)
ref = np.asarray(Image.open(ref_path).convert('RGB'), dtype=np.float32) / 255.0
rh, rw = ref.shape[:2]
def small(a):  # vidurkis blokais iki ref dydžio
    k = a.shape[1] // rw
    return a[:rh * k, :rw * k].reshape(rh, k, rw, k, 3).mean((1, 3))
s = small(img)
def blk(a, w):  # bloko vidurkis iki pločio w (Paukščių Takas – didelės struktūros, ne pavienės žvaigždės)
    k = a.shape[1] // w; h = a.shape[0] // k
    return a[:h * k, :w * k].reshape(h, k, w, k).mean((1, 3))
def rank(a):
    r = np.empty(a.size); r[np.argsort(a.ravel())] = np.arange(a.size); return r.reshape(a.shape)
lum_s = s.mean(-1); lum_r = ref.mean(-1)
W0 = 512
cs = rank(blk(lum_s, W0)); cr = rank(blk(lum_r, W0))
best = None
for fv in (False, True):
    for fh in (False, True):
        a = cs[::-1] if fv else cs
        a = a[:, ::-1] if fh else a
        for sh0 in range(W0):
            b = np.roll(a, sh0, axis=1)
            r = float(np.corrcoef(b.ravel(), cr.ravel())[0, 1])
            if best is None or r > best[0]: best = (r, fv, fh, sh0)
        print('flipV', fv, 'flipH', fh, 'geriausias iki šiol', best)
r0, fv, fh, sh0 = best
# patikslinimas 4k raiška (blokai 1024) aplink rastą poslinkį
k0 = rw // W0
a = lum_s[::-1] if fv else lum_s
a = a[:, ::-1] if fh else a
fa = rank(blk(a, 1024)); fr = rank(blk(lum_r, 1024))
bb = None
for d in range(-2 * (1024 // W0), 2 * (1024 // W0) + 1):
    sh1 = (sh0 * (1024 // W0) + d) % 1024
    r = float(np.corrcoef(np.roll(fa, sh1, axis=1).ravel(), fr.ravel())[0, 1])
    if bb is None or r > bb[0]: bb = (r, sh1)
sh = (bb[1] * (rw // 1024)) % rw
best = (bb[0], fv, fh, sh)
r, fv, fh, sh = best
print('GERIAUSIA', best)
if r < 0.6: print('per maža koreliacija – 8k nekuriamas'); sys.exit(0)
if fv: img = img[::-1]
if fh: img = img[:, ::-1]
k = img.shape[1] // rw
img = np.roll(img, sh * k, axis=1)
s = small(img)
# tonai: kiekvienam kanalui kvantilių atitikimas su esamu 4k fonu
outimg = np.empty(img.shape, dtype=np.uint8)
q = np.linspace(0, 1, 513)
for c in range(3):
    src = np.quantile(s[..., c], q); dst = np.quantile(ref[..., c], q)
    src = np.maximum.accumulate(src + np.arange(len(src)) * 1e-12)
    outimg[..., c] = np.clip(np.interp(img[..., c], src, dst) * 255 + 0.5, 0, 255).astype(np.uint8)
Image.fromarray(outimg).save(out, quality=90, optimize=True)
print('įrašyta', out, outimg.shape)
# v3

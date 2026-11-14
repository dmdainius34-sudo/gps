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
lum_s = np.log1p(s.mean(-1) * 1000); lum_r = ref.mean(-1)
best = None
for fv in (False, True):
    for fh in (False, True):
        a = lum_s[::-1] if fv else lum_s
        a = a[:, ::-1] if fh else a
        A = np.fft.rfft(a - a.mean(), axis=1); B = np.fft.rfft(lum_r - lum_r.mean(), axis=1)
        cc = np.fft.irfft((A * np.conj(B)).sum(0), n=rw)
        k = int(np.argmax(cc)); sh = (-k) % rw
        b = np.roll(a, sh, axis=1)
        r = float(np.corrcoef(b.ravel(), lum_r.ravel())[0, 1])
        print('flipV', fv, 'flipH', fh, 'shift', sh, 'corr', round(r, 3))
        if best is None or r > best[0]: best = (r, fv, fh, sh)
r, fv, fh, sh = best
print('GERIAUSIA', best)
if r < 0.5: print('per maža koreliacija – 8k nekuriamas'); sys.exit(0)
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

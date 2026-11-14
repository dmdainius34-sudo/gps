"""NASA/JPL-Caltech Paukščių Tako vaizdas -> assets/milkyway.jpg (kvadratas 2048 px, centras – galaktikos centras)."""
import sys
from PIL import Image, ImageStat

src, dst = sys.argv[1], sys.argv[2]
im = Image.open(src).convert("RGB")
w, h = im.size
s = min(w, h)
im = im.crop(((w - s) // 2, (h - s) // 2, (w - s) // 2 + s, (h - s) // 2 + s)).resize((2048, 2048), Image.LANCZOS)
g = im.convert("L")
c = ImageStat.Stat(g.crop((924, 924, 1124, 1124))).mean[0]
e = ImageStat.Stat(g.crop((0, 0, 200, 200))).mean[0]
print(f"šaltinis {w}x{h}; centro šviesumas {c:.0f}, kampo {e:.0f}")
if c < 60 or e > 40:
    sys.exit("netinka: centre turi būti šviesus branduolys, kampuose – tamsu")
im.save(dst, quality=88, optimize=True)
print("įrašyta", dst)

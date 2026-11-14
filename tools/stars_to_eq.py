#!/usr/bin/env python3
"""Žvaigždžių fono konvertavimas: galaktinės koordinatės (centras l=0, b=0; l didėja į kairę; b=+90 apačioje) ->
lygiakampė RA/Dec (u: RA −180..180, v: Dec +90..−90), IAU J2000 matrica. Naudojimas: stars_to_eq.py įvestis išvestis.jpg"""
import sys, numpy as np
from PIL import Image
s = np.asarray(Image.open(sys.argv[1]).convert('RGB')).astype(np.float32); H, W = s.shape[:2]
M = np.array([[-0.0548755604, -0.8734370902, -0.4838350155], [0.4941094279, -0.4448296300, 0.7469822445],
              [-0.8676661490, -0.1980763734, 0.4559837762]])          # pusiaujo -> galaktinė
OW, OH = 4096, 2048
ra = np.radians(-180 + 360 * (np.arange(OW) + 0.5) / OW); de = np.radians(90 - 180 * (np.arange(OH) + 0.5) / OH)
RA, DE = np.meshgrid(ra, de)
g = np.stack([np.cos(DE) * np.cos(RA), np.cos(DE) * np.sin(RA), np.sin(DE)], -1) @ M.T
l = np.degrees(np.arctan2(g[..., 1], g[..., 0])); b = np.degrees(np.arcsin(np.clip(g[..., 2], -1, 1)))
xi = (W / 2 - l / 360 * W - 0.5) % W; yi = np.clip(H / 2 + b / 180 * H - 0.5, 0, H - 1)   # šaltinyje galaktikos šiaurė apačioje
x0 = np.floor(xi).astype(int); y0 = np.floor(yi).astype(int); x1 = (x0 + 1) % W; y1 = np.minimum(y0 + 1, H - 1)
ax = (xi - x0)[..., None]; ay = (yi - y0)[..., None]
out = (s[y0, x0] * (1 - ax) + s[y0, x1] * ax) * (1 - ay) + (s[y1, x0] * (1 - ax) + s[y1, x1] * ax) * ay
Image.fromarray(np.clip(out * 2.2, 0, 255).astype(np.uint8)).save(sys.argv[2], quality=92)

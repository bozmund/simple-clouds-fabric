"""Texture inside the horizon band of single frames: python3 tools/bandtex.py <png>...
For rows 260-319 (the S5 horizon band), averages luminance per column, removes the broad shape
with a 41-px moving average and prints the remaining rms (streak strength, 0-255 levels) and the
strongest autocorrelation lags between 8 and 120 px (a regular streak spacing shows up here;
dither noise does not). Needs Pillow."""
import sys

from PIL import Image

for f in sys.argv[1:]:
    im = Image.open(f).convert("L")
    w, h = im.size
    px = im.load()
    y0, y1 = 260, min(h, 320)
    prof = [sum(px[x, y] for y in range(y0, y1)) / (y1 - y0) for x in range(w)]
    hp = []
    for x in range(w):
        a, b = max(0, x - 20), min(w, x + 21)
        hp.append(prof[x] - sum(prof[a:b]) / (b - a))
    var = sum(v * v for v in hp) / w
    ac = [(lag, sum(hp[x] * hp[x + lag] for x in range(w - lag)) / ((w - lag) * max(var, 1e-9))) for lag in range(8, 121)]
    best = sorted(ac, key=lambda t: -t[1])[:3]
    print(f"{f}: band mean {sum(prof) / w:6.1f}  streak rms {var ** 0.5:5.2f}  lags " + ", ".join(f"{l}:{r:.2f}" for l, r in best))

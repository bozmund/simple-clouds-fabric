"""Per-frame brightness in three bands: python3 tools/roi.py <evidence-dir>
sky = 10-90 % width, 0-35 % height; horizon = 10-90 % width, 50-62 % height;
terrain = 10-90 % width, 80-98 % height (the same box as flash-metrics.py).
Prints mean luminance per band and marks a frame '*' when a band is 4+ levels brighter
than the median of that band over the S5 series (a flash candidate). Needs Pillow."""
import glob
import os
import statistics
import sys

from PIL import Image

BANDS = {"sky": (0.10, 0.00, 0.90, 0.35), "horizon": (0.10, 0.50, 0.90, 0.62), "terrain": (0.10, 0.80, 0.90, 0.98)}


def band_means(path):
    im = Image.open(path).convert("L")
    w, h = im.size
    out = {}
    for name, (x0, y0, x1, y1) in BANDS.items():
        crop = im.crop((int(x0 * w), int(y0 * h), int(x1 * w), int(y1 * h)))
        px = list(crop.getdata())
        out[name] = sum(px) / len(px)
    return out


d = sys.argv[1]
files = sorted(glob.glob(os.path.join(d, "devshot-*.png")))
rows = [(os.path.basename(f), band_means(f)) for f in files]
series = [m for n, m in rows if n.startswith("devshot-S5-")]
median = {b: statistics.median(m[b] for m in series) for b in BANDS} if series else None
for name, m in rows:
    flags = ""
    if median and name.startswith("devshot-S5-"):
        flags = " ".join(f"*{b}+{m[b] - median[b]:.1f}" for b in BANDS if m[b] - median[b] >= 4)
    print(f"{name:22s} " + " ".join(f"{b} {m[b]:6.1f}" for b in BANDS) + (f"  {flags}" if flags else ""))
if median:
    print("S5 median  " + " ".join(f"{b} {median[b]:.1f}" for b in BANDS))

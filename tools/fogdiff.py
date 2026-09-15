"""Isolate what the storm fog pass does to one frame: python3 tools/fogdiff.py <fog.png> <nofog.png> <out-prefix>
Only valid for a scene-identical pair (same faces= at the shot, e.g. 21-storm-b / 22-storm-nofog).
Writes <out>-diff.png (darkening x4, white = no change) and prints:
- the rows where the fog darkens (mean darkening per row band),
- the darkening profile across columns inside the band and its autocorrelation peaks
  (a regular spacing = structure from the coverage cells, not noise). Needs Pillow."""
import sys

from PIL import Image

fog = Image.open(sys.argv[1]).convert("L")
nof = Image.open(sys.argv[2]).convert("L")
w, h = fog.size
pf, pn = fog.load(), nof.load()
dark = [[max(0, pn[x, y] - pf[x, y]) for x in range(w)] for y in range(h)]

out = Image.new("L", (w, h))
po = out.load()
for y in range(h):
    for x in range(w):
        po[x, y] = 255 - min(255, dark[y][x] * 4)
out.save(sys.argv[3] + "-diff.png")

rows = [sum(r) / w for r in dark]
print("row bands (mean darkening, 0-255):")
for y0 in range(0, h, 20):
    band = rows[y0:y0 + 20]
    print(f"  y {y0:3d}-{y0 + 19:3d}: {sum(band) / len(band):6.2f}")
peak = max(range(h), key=lambda y: rows[y])
y0, y1 = max(0, peak - 6), min(h, peak + 7)
prof = [sum(dark[y][x] for y in range(y0, y1)) / (y1 - y0) for x in range(w)]
mean = sum(prof) / w
print(f"band rows {y0}-{y1 - 1} (peak row {peak}): column mean {mean:.2f}, min {min(prof):.2f}, max {max(prof):.2f}")
# high-pass: subtract a 41-px moving average, so only streak-scale structure remains
hp = []
for x in range(w):
    a, b = max(0, x - 20), min(w, x + 21)
    hp.append(prof[x] - sum(prof[a:b]) / (b - a))
var = sum(v * v for v in hp) / w
print(f"streak amplitude (rms after 41-px high-pass): {var ** 0.5:.2f}")
ac = []
for lag in range(2, 120):
    ac.append((lag, sum(hp[x] * hp[x + lag] for x in range(w - lag)) / ((w - lag) * max(var, 1e-9))))
best = sorted(ac, key=lambda t: -t[1])[:5]
print("autocorrelation peaks (lag px, r):", ", ".join(f"{l}:{r:.2f}" for l, r in best))
print("profile every 8 px:", " ".join(f"{prof[x]:.0f}" for x in range(0, w, 8)))

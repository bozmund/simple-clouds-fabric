"""Difference of two shots of the same view, split into geometry and sky pixels:
   python3 tools/maskdiff.py <fogdebug.png> <a.png> <b.png>
The mask comes from a FOGDEBUG shot of the same fixed view: its sky is painted (51, 102, 255)
(blue clearly above red, even with the atmospheric layer blended on top), everything else
(terrain, clouds; grey in debug mode) is geometry. For each class prints the mean absolute RGB
difference (0-255) between a and b and the share of pixels differing by more than 8 levels.
Used for the atmospheric layer: a = layer on, b = OVL0 (layer off). Needs Pillow."""
import sys

from PIL import Image

mask = Image.open(sys.argv[1]).convert("RGB")
a = Image.open(sys.argv[2]).convert("RGB")
b = Image.open(sys.argv[3]).convert("RGB")
if not (mask.size == a.size == b.size):
    sys.exit(f"size mismatch {mask.size} {a.size} {b.size}")
pm, pa, pb = mask.load(), a.load(), b.load()
w, h = a.size
stats = {"geometry": [0, 0.0, 0], "sky": [0, 0.0, 0]}
for y in range(h):
    for x in range(w):
        r, g, bl = pm[x, y]
        # debug sky (51, 102, 255), possibly with some white layer blended in; geometry is grey
        cls = "sky" if bl - r > 40 else "geometry"
        d = [abs(u - v) for u, v in zip(pa[x, y], pb[x, y])]
        s = stats[cls]
        s[0] += 1
        s[1] += sum(d) / 3.0
        s[2] += 1 if max(d) > 8 else 0
for cls, (n, tot, big) in stats.items():
    if n:
        print(f"{cls:8s} pixels {n:6d}  mean abs diff {tot / n:6.2f}  share >8 levels {big / n:.3f}")

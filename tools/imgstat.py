"""Luminance statistics of screenshots: python3 tools/imgstat.py <png>...
Prints mean / standard deviation of luminance and the share of pure-white pixels
(a uniform 255 frame = something painted the whole screen). Needs Pillow (see sheet.py)."""
import sys

from PIL import Image

for f in sys.argv[1:]:
    im = Image.open(f).convert("RGB")
    px = list(im.getdata())
    n = len(px)
    lum = [0.299 * r + 0.587 * g + 0.114 * b for r, g, b in px]
    mean = sum(lum) / n
    var = sum((l - mean) ** 2 for l in lum) / n
    white = sum(1 for r, g, b in px if r > 250 and g > 250 and b > 250) / n
    print(f"{f}: mean {mean:.1f} std {var ** 0.5:.1f} pure-white {white:.3f} size {im.size}")

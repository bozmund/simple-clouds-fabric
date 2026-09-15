"""Compare two evidence folders frame by frame: python3 tools/framediff.py <dirA> <dirB>
For every devshot-*.png present in both: mean absolute RGB difference (0-255) and the share of
pixels differing by more than 24 levels in any channel. Identical scenes give ~0; a different
storm size or cloud phase gives tens. Needs Pillow."""
import glob
import os
import sys

from PIL import Image, ImageChops

a, b = sys.argv[1], sys.argv[2]
names = sorted(set(os.path.basename(f) for f in glob.glob(os.path.join(a, "devshot-*.png")))
               & set(os.path.basename(f) for f in glob.glob(os.path.join(b, "devshot-*.png"))))
total = []
for n in names:
    ia = Image.open(os.path.join(a, n)).convert("RGB")
    ib = Image.open(os.path.join(b, n)).convert("RGB")
    if ia.size != ib.size:
        print(f"{n:22s} size differs {ia.size} vs {ib.size}")
        continue
    diff = ImageChops.difference(ia, ib)
    hist = diff.histogram()  # 3 x 256 bins
    npx = ia.size[0] * ia.size[1]
    mean = sum(i * c for ch in range(3) for i, c in enumerate(hist[ch * 256:(ch + 1) * 256])) / (3 * npx)
    big = sum(1 for px in diff.getdata() if max(px) > 24) / npx
    total.append(mean)
    print(f"{n:22s} mean abs diff {mean:6.2f}  pixels >24: {big:.3f}")
if total:
    print(f"frames {len(total)}  mean {sum(total) / len(total):.2f}  max {max(total):.2f}")

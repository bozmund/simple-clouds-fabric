"""Frame-to-frame brightness in a fixed terrain ROI, not a visual-parity test."""
from pathlib import Path
from PIL import Image, ImageStat, ImageChops
import sys

root = Path(sys.argv[1])
previous = None
for path in sorted(root.glob('devshot-S5-*.png')):
    image = Image.open(path).convert('RGB')
    w, h = image.size
    terrain = image.crop((int(w * .1), int(h * .80), int(w * .9), int(h * .98))).convert('L')
    mean = ImageStat.Stat(terrain).mean[0]
    delta = ImageStat.Stat(ImageChops.difference(terrain, previous)).mean[0] if previous else 0
    print(f'{path.name}: terrain_mean={mean:.3f} mean_abs_delta={delta:.3f}')
    previous = terrain

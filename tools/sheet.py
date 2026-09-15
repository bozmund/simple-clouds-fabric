"""Contact sheet of dev screenshots: python3 tools/sheet.py '<glob>' <out.png>
(7 columns, 320 px wide tiles, file name above each tile). Needs Pillow:
  nix shell --impure --expr '(builtins.getFlake "path:/home/jan/nixos-symbiote").inputs.nixpkgs.legacyPackages.x86_64-linux.python3.withPackages (ps: [ ps.pillow ])'
"""
import glob
import sys

from PIL import Image, ImageDraw

files = sorted(glob.glob(sys.argv[1]))
if not files:
    sys.exit("no files match " + sys.argv[1])
tw, cols = 320, 7
ims = [Image.open(f).convert("RGB") for f in files]
th = int(ims[0].height * tw / ims[0].width)
rows = (len(ims) + cols - 1) // cols
sheet = Image.new("RGB", (cols * tw, rows * (th + 14)), (20, 20, 24))
d = ImageDraw.Draw(sheet)
for i, (f, im) in enumerate(zip(files, ims)):
    x, y = (i % cols) * tw, (i // cols) * (th + 14)
    sheet.paste(im.resize((tw, th)), (x, y + 14))
    d.text((x + 3, y + 1), f.split("devshot-")[-1], fill=(255, 214, 102))
sheet.save(sys.argv[2])
print(len(files), "frames ->", sys.argv[2], sheet.size, "source size", ims[0].size)

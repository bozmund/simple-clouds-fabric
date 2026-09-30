"""Descriptive consecutive-frame changes, not a visual acceptance test.

Usage: python3 tools/motion-metrics.py evidence-directory
Requires Pillow. Camera/timing must be matched before comparing runs.
"""
from pathlib import Path
import sys
import re
from PIL import Image, ImageChops

def frame_number(path):
    match = re.fullmatch(r"devshot-SHAKE-(\d+)(?:-(\d+))?\.png", path.name)
    if match is None:
        raise SystemExit(f"Unrecognized SHAKE frame name: {path.name}")
    # Forge names its first frame '-01.png', then '-01-02.png' etc.
    # Lexical sorting puts the first frame LAST, creating a false final jump.
    return int(match.group(2) or match.group(1))

files = sorted(Path(sys.argv[1]).glob("devshot-SHAKE-*.png"), key=frame_number)
if len(files) != 41:
    raise SystemExit(f"Incomplete SHAKE sequence: expected 41 frames, got {len(files)}")
if [frame_number(path) for path in files] != list(range(1, 42)):
    raise SystemExit("SHAKE frame indices are missing or duplicated")
changes = []
previous = None
for path in files:
    with Image.open(path) as source:
        frame = source.convert("RGB")
    if previous is not None:
        if previous.size != frame.size:
            raise SystemExit("Frame dimensions changed")
        difference = ImageChops.difference(frame, previous)
        histogram = difference.histogram()
        pixels = frame.width * frame.height
        mean = sum(i * count for channel in range(3)
                   for i, count in enumerate(histogram[channel*256:(channel+1)*256])) / (3*pixels)
        changes.append(mean)
        print(f"{path.name}: RGB mean absolute change={mean:.6f}/255")
    previous = frame
print(f"frames={len(files)} mean_change={sum(changes)/len(changes):.6f} max_change={max(changes):.6f}")
print("Descriptive only: cannot establish smoothness or improvement without visual review and a matched baseline.")

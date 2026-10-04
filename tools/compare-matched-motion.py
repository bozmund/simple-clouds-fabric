#!/usr/bin/env python3
"""Descriptive image evidence only, not a release/parity acceptance test.

Pair original/port by logged noise angle rather than screenshot index: their
startup and terrain-settle durations differ. Never invent phase-aligned pairs.
Run with the repository's pinned nixpkgs Python/Pillow runtime.
"""
import argparse
import json
import re
from pathlib import Path
from PIL import Image, ImageChops, ImageStat

CAPTURE = re.compile(
    r"matched capture (\S+) tick=(\d+) angle=([\d.eE+-]+) scroll=([^ ]+) "
    r"viewport=(\d+)x(\d+) camera=([^ ]+)"
)


def captures(root):
    records = []
    for line in (root / "test/latest.log").read_text().splitlines():
        match = CAPTURE.search(line)
        if not match:
            continue
        name, tick, angle, scroll, width, height, camera = match.groups()
        records.append(dict(name=name, tick=int(tick), angle=float(angle),
                            scroll=scroll, width=int(width), height=int(height), camera=camera))
    if len(records) != 41 or len({r["name"] for r in records}) != 41:
        raise ValueError(f"Expected 41 unique capture records: {root}")
    if any((r["width"], r["height"], r["camera"]) != (1280, 720, "0.0,80.0,0.0") for r in records):
        raise ValueError(f"Unmatched viewport/camera: {root}")
    if any(b["angle"] <= a["angle"] for a, b in zip(records, records[1:])):
        raise ValueError(f"Non-monotonic noise phase (possible server correction): {root}")
    return records


def sky(root, record):
    with Image.open(root / "test/screenshots" / record["name"]) as image:
        if image.size != (1280, 720):
            raise ValueError("Actual image dimensions do not match logged viewport")
        # Fixed upper-sky crop excludes differing terrain/horizon presentation.
        return image.convert("RGB").crop((100, 0, 1180, 560))


def mask(image):
    r, g, b = image.split()
    return ImageChops.darker(ImageChops.darker(r, g), b).point(lambda v: 255 if v >= 170 else 0)


def count(image):
    return image.histogram()[255]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("original", type=Path)
    parser.add_argument("port", type=Path)
    parser.add_argument("--require-common-start", action="store_true",
                        help="Reject runs that did not hold phase 0.75 until first capture")
    args = parser.parse_args()
    original, port = captures(args.original), captures(args.port)
    if args.require_common_start and any(abs(records[0]["angle"] - 0.75) > 0.00015 for records in (original, port)):
        raise ValueError("Initial phase was not held at 0.75 in both runs")
    pairs = []
    for a in original:
        b = min(port, key=lambda p: abs(p["angle"] - a["angle"]))
        error = abs(b["angle"] - a["angle"])
        if error > 0.00015:  # at most ~1.5 ticks at the fixture speed
            continue
        ia, ib = sky(args.original, a), sky(args.port, b)
        ma, mb = mask(ia), mask(ib)
        union = count(ImageChops.lighter(ma, mb))
        pairs.append(dict(original=a["name"], port=b["name"], angle_error=error,
                          sky_rgb_mae_255=sum(ImageStat.Stat(ImageChops.difference(ia, ib)).mean) / 3,
                          bright_mask_iou=count(ImageChops.darker(ma, mb)) / union if union else None))
    if not pairs:
        raise ValueError("No sufficiently phase-aligned pairs; do not compare by frame number")
    temporal = {}
    for name, root, records in [("original", args.original, original), ("port", args.port, port)]:
        values = []
        for a, b in zip(records, records[1:]):
            diff = ImageChops.difference(sky(root, a), sky(root, b))
            values.append(dict(tick_delta=b["tick"] - a["tick"], angle_delta=b["angle"] - a["angle"],
                               sky_rgb_mae_255=sum(ImageStat.Stat(diff).mean) / 3))
        temporal[name] = dict(mean_sky_rgb_change_255=sum(v["sky_rgb_mae_255"] for v in values) / len(values),
                              max_sky_rgb_change_255=max(v["sky_rgb_mae_255"] for v in values), steps=values)
    print(json.dumps(dict(
        scope="observed descriptive measurements; not smoothness, motion-fix or full parity acceptance",
        crop=[100, 0, 1180, 560], mask="all RGB channels >=170; heuristic, not semantic geometry truth",
        phase_tolerance=0.00015, common_start_required=args.require_common_start,
        original_angle_range=[original[0]["angle"], original[-1]["angle"]],
        port_angle_range=[port[0]["angle"], port[-1]["angle"]], pairs=pairs, temporal=temporal), indent=2))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Compare fixed-camera SHAKE captures; reports sky-image change, not visual parity.

An observed lightning flash can be excluded by the number of the frame ending
the affected comparison. Keep the original frames and report exclusions; pixel
change alone cannot distinguish a flash from a cloud-geometry discontinuity.
"""
import argparse
import glob
import statistics
from PIL import Image, ImageChops, ImageStat


def analyze(directory, excluded_ends=frozenset()):
    paths = sorted(glob.glob(f"{directory}/devshot-SHAKE-*.png"),
                   key=lambda path: int(path.rsplit("-", 1)[-1].removesuffix(".png")))
    if len(paths) < 2:
        raise SystemExit(f"Need at least two SHAKE frames in {directory}")
    values = []
    excluded = []
    previous = None
    previous_luma = None
    for path in paths:
        with Image.open(path) as loaded:
            image = loaded.convert("RGB")
            image = image.crop((0, 0, image.width, image.height * 3 // 5))
        luma = ImageStat.Stat(image.convert("L")).mean[0]
        if previous is not None:
            diff = ImageChops.difference(previous, image)
            mean = sum(ImageStat.Stat(diff).mean) / 3.0
            changed = diff.convert("L").point(lambda pixel: 255 if pixel > 12 else 0)
            fraction = changed.histogram()[255] / (image.width * image.height)
            frame = int(path.rsplit("-", 1)[-1].removesuffix(".png"))
            item = (path, mean, fraction, luma - previous_luma)
            (excluded if frame in excluded_ends else values).append(item)
        previous = image
        previous_luma = luma
    if not values:
        raise SystemExit(f"No non-excluded adjacent comparisons in {directory}")
    means = sorted(item[1] for item in values)
    fractions = sorted(item[2] for item in values)
    p95 = min(len(values) - 1, int(len(values) * 0.95))
    print(f"{directory}: {len(paths)} frames, upper 60% of image")
    if excluded:
        print(f"  excluded ending frames: {', '.join(str(int(item[0].rsplit('-', 1)[-1].removesuffix('.png'))) for item in excluded)}")
    print(f"  mean RGB delta median={statistics.median(means):.3f}, p95={means[p95]:.3f}, max={means[-1]:.3f}")
    print(f"  pixels changed >12/255 median={statistics.median(fractions):.3%}, p95={fractions[p95]:.3%}, max={fractions[-1]:.3%}")
    for path, mean, fraction, luma_delta in sorted(values, key=lambda item: item[1], reverse=True)[:5]:
        print(f"  largest: {path.rsplit('/', 1)[-1]} mean={mean:.3f}, changed={fraction:.3%}, signed_luma_delta={luma_delta:+.3f}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directories", nargs="+", help="directories of numbered SHAKE PNGs")
    parser.add_argument("--exclude-end", default="", help="comma-separated frame numbers whose preceding comparisons contain a verified flash")
    args = parser.parse_args()
    excluded_ends = frozenset(int(frame) for frame in args.exclude_end.split(",") if frame)
    for directory in args.directories:
        analyze(directory, excluded_ends)

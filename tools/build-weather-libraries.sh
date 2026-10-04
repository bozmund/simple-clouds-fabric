#!/usr/bin/env bash
# Reproducible source inputs for C10. Builds libraries; does not enable/install effects.
set -euo pipefail
cd "$(cd "$(dirname "$0")/.." && pwd -P)"
project=$(pwd -P)
for tool in curl tar sha256sum systemd-run systemctl; do
  command -v "$tool" >/dev/null || { echo "Missing dependency: $tool" >&2; exit 1; }
done
for unit in simpleclouds-devclient simpleclouds-realgame simpleclouds-refclient simpleclouds-servercheck; do
  if systemctl --user is-active --quiet "$unit"; then
    echo "Refusing compilation during owned runtime test: $unit" >&2; exit 1
  fi
done
[[ ! -L build && ! -L build/weather-libraries ]] || { echo 'Unsafe output root' >&2; exit 1; }
output="$project/build/weather-libraries"
mkdir -p "$output"

build_library() {
  local repo=$1 revision=$2 expected=$3 unit=$4 task=$5 artifact=$6 destination=$7
  local archive="$output/$repo-$revision.tar.gz" upstream
  case "$repo" in
    particle-rain) upstream=PigCart/particle-rain ;;
    immersive-storms) upstream=TheDeathlyCow/immersive-storms ;;
    *) echo 'Unknown library' >&2; return 1 ;;
  esac
  [[ ! -L "$archive" && ! -L "$output/$destination" && ! -L "$output/$destination.build.log" ]] || { echo 'Unsafe library path' >&2; return 1; }
  [[ ! -e "$output/$destination" || -f "$output/$destination" ]] || { echo 'Unexpected library output type' >&2; return 1; }
  if [[ ! -e "$archive" ]]; then
    local download
    download=$(mktemp "$output/download-XXXXXX")
    if ! curl --fail --location --silent --show-error "https://codeload.github.com/$upstream/tar.gz/$revision" --output "$download"; then
      echo "Download failed; incomplete artifact retained at $download" >&2; return 1
    fi
    [[ "$(sha256sum "$download" | cut -d ' ' -f1)" == "$expected" ]] || {
      echo "Unexpected source content; retained at $download, not accepted" >&2; return 1;
    }
    mv -- "$download" "$archive"
  fi
  [[ -f "$archive" && "$(sha256sum "$archive" | cut -d ' ' -f1)" == "$expected" ]] || {
    echo "Pinned source checksum mismatch: $archive" >&2; return 1;
  }
  if tar -tzf "$archive" | grep -E '(^/|(^|/)\.\.(/|$))' >/dev/null; then echo 'Unsafe source archive paths' >&2; return 1; fi
  if tar -tvzf "$archive" | grep -E '^[^d-]' >/dev/null; then echo 'Nonregular source archive entries' >&2; return 1; fi
  # Fresh extraction: never accept modified files in a previous build as pinned source.
  local stage root
  stage=$(mktemp -d "$output/source-$repo-XXXXXX")
  root="$stage/$repo-$revision"
  tar --extract --gzip --file="$archive" --directory="$stage" --no-same-owner --keep-old-files
  [[ -f "$root/gradlew" && -f "$root/LICENSE" ]] || { echo 'Unexpected source layout' >&2; return 1; }
  if systemctl --user is-active --quiet "$unit"; then echo "Build already running: $unit" >&2; return 1; fi
  systemd-run --user --unit="$unit" --collect --wait --pipe \
    -p MemoryMax=4G -p RuntimeMaxSec=600 -p "WorkingDirectory=$root" \
    /run/current-system/sw/bin/bash ./gradlew --configure-on-demand --no-daemon --max-workers=2 \
    -Dorg.gradle.jvmargs=-Xmx2G "$task" >"$output/$destination.build.log" 2>&1 || {
      echo "Library build failed: $output/$destination.build.log" >&2; return 1;
    }
  [[ -f "$root/$artifact" && ! -L "$root/$artifact" ]] || { echo 'Build produced no expected library' >&2; return 1; }
  cp -- "$root/$artifact" "$output/$destination"
  [[ ! -L "$output/$repo.LICENSE" ]] || { echo 'Unsafe license output' >&2; return 1; }
  cp -- "$root/LICENSE" "$output/$repo.LICENSE"
  sha256sum "$output/$destination"
}

# Use explicit supported inputs instead of accepting arbitrary download locations.
build_library particle-rain ff0e693a11a38fad454be72527ef605484e61c69 \
  68b0b8d318d42c34b934c8500dec5ddc4536c29bb70d6f5a91ca11f9e8309f97 \
  simpleclouds-weather-particle-build :26.3-fabric:build \
  versions/26.3-fabric/build/libs/particlerain-4.0.0-beta.100+26.3-fabric.jar particle-rain.jar
build_library immersive-storms 157767f062f464678f96af3c104def5c1f721ac3 \
  706ac420a2dfec3c535d988303d8c6df7eed91e502bd1f1b8294b71aded08107 \
  simpleclouds-weather-storm-build build \
  build/libs/immersive-storms-1.8.0+26.3.jar immersive-storms.jar
gpl="$output/GPL-3.0.LICENSE"
[[ ! -L "$gpl" ]] || { echo 'Unsafe GPL license path' >&2; exit 1; }
if [[ ! -e "$gpl" ]]; then
  curl --fail --location --silent --show-error https://www.gnu.org/licenses/gpl-3.0.txt --output "$gpl"
fi
[[ -f "$gpl" && "$(sha256sum "$gpl" | cut -d ' ' -f1)" == 3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986 ]] || {
  echo 'Unexpected GPL license text; not accepted' >&2; exit 1;
}
echo 'Libraries built from verified source; effects not installed or enabled.'

#!/usr/bin/env bash
# Fresh project/output directories, but existing external dependency/JDK cache.
# This checks source completeness; it is not a fresh-machine/offline bootstrap.
set -euo pipefail
cd "$(cd "$(dirname "$0")/.." && pwd -P)"
project=$(pwd -P)
[[ $# == 1 ]] || { echo 'Usage: verify-private-source-rebuild.sh <private-handoff-directory>' >&2; exit 2; }
bundle=$(realpath -e -- "$1")
case "$bundle" in "$project"/build/private-handoff-*) ;; *) echo 'Refusing unowned bundle location' >&2; exit 2;; esac
[[ -f "$bundle/SHA256SUMS" && ! -L "$bundle/SHA256SUMS" ]]
for unit in simpleclouds-realgame simpleclouds-devclient simpleclouds-refclient simpleclouds-servercheck; do
  if systemctl --user is-active --quiet "$unit"; then echo "Refusing build during owned runtime: $unit" >&2; exit 1; fi
done
for pid in $(pgrep -x 'java|\.java-wrapped' 2>/dev/null || true); do
  if tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | grep -qE 'KnotClient|cpw\.mods\.bootstraplauncher\.BootstrapLauncher|net\.minecraftforge\.userdev\.LaunchTesting'; then
    echo "Refusing build during Minecraft PID $pid" >&2; exit 1
  fi
done
bash tools/test-memory-budget.sh check
(cd "$bundle"; sha256sum --check SHA256SUMS)
if tar -tzf "$bundle/port-source.tar.gz" | grep -E '(^/|(^|/)\.\.(/|$))' >/dev/null; then echo 'Unsafe source path' >&2; exit 1; fi
if tar -tvzf "$bundle/port-source.tar.gz" | grep -E '^[^d-]' >/dev/null; then echo 'Linked/special source entry rejected' >&2; exit 1; fi
rebuild=$(mktemp -d "$bundle/clean-source-rebuild-XXXXXX")
tar --extract --gzip --file="$bundle/port-source.tar.gz" --directory="$rebuild" --no-same-owner --keep-old-files
mkdir -p "$rebuild/build/weather-libraries"
artifact=simple-clouds-0.7.3+26.3-fabric.jar
# Separately rebuilt upstream modules are checked byte-for-byte by the package
# verifier. Reuse those prerequisites here; do not claim rebuilding upstreams.
while IFS= read -r -d '' kv; do export "$kv"; done < /home/jan/.cache/simpleclouds/launch.env
command -v jar >/dev/null || { echo 'Java25 jar tool required' >&2; exit 1; }
mkdir "$rebuild/prerequisites"
(cd "$rebuild/prerequisites"; jar xf "$bundle/$artifact" META-INF/jars/particle-rain.jar META-INF/jars/immersive-storms.jar META-INF/licenses/simpleclouds-weather/particle-rain.LICENSE META-INF/licenses/simpleclouds-weather/immersive-storms.LICENSE META-INF/licenses/simpleclouds-weather/GPL-3.0.LICENSE)
cp -- "$rebuild/prerequisites/META-INF/jars/particle-rain.jar" "$rebuild/prerequisites/META-INF/jars/immersive-storms.jar" "$rebuild/build/weather-libraries/"
cp -- "$rebuild/prerequisites/META-INF/licenses/simpleclouds-weather/"*.LICENSE "$rebuild/build/weather-libraries/"
cd "$rebuild"
./gradlew --offline --no-daemon --max-workers=4 -PintegratedWeather=true -PdhApiJar=/home/jan/src/distant-horizons/coreSubProjects/api/build/libs/DistantHorizonsApi-7.1.0.jar build > "$bundle/clean-source-build.log" 2>&1
cmp -s "$bundle/$artifact" "build/libs/$artifact" || {
  echo 'Fresh-source artifact differs; inspect before claiming reproducibility' >&2
  sha256sum "$bundle/$artifact" "build/libs/$artifact"; exit 1
}
echo "PASS byte-identical fresh-project rebuild using cached dependencies: $rebuild"
sha256sum "build/libs/$artifact"

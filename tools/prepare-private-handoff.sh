#!/usr/bin/env bash
# Private build/source packaging only. No install, Git change or publishing.
set -euo pipefail
cd "$(cd "$(dirname "$0")/.." && pwd -P)"
project=$(pwd -P)
for unit in simpleclouds-realgame simpleclouds-devclient simpleclouds-refclient simpleclouds-servercheck; do
  if systemctl --user is-active --quiet "$unit"; then echo "Refusing build during owned runtime: $unit" >&2; exit 1; fi
done
for pid in $(pgrep -x 'java|\.java-wrapped' 2>/dev/null || true); do
  if tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | grep -qE 'KnotClient|cpw\.mods\.bootstraplauncher\.BootstrapLauncher|net\.minecraftforge\.userdev\.LaunchTesting'; then
    echo "Refusing build during Minecraft PID $pid" >&2; exit 1
  fi
done
bash tools/test-memory-budget.sh check
[[ ! -L build ]] || { echo 'Unsafe build root' >&2; exit 1; }
for path in src gradle tools; do
  [[ -d "$path" && ! -L "$path" ]] || { echo "Unsafe source root $path" >&2; exit 1; }
  [[ -z "$(find "$path" -type l -print -quit)" ]] || { echo "Linked source rejected: $path" >&2; exit 1; }
done
output=$(mktemp -d "$project/build/private-handoff-XXXXXX")
mkdir "$output/upstream-source"
for name in particle-rain-ff0e693a11a38fad454be72527ef605484e61c69.tar.gz immersive-storms-157767f062f464678f96af3c104def5c1f721ac3.tar.gz; do
  [[ -f "build/weather-libraries/$name" && ! -L "build/weather-libraries/$name" ]] || { echo "Missing/unsafe pinned source $name" >&2; exit 1; }
done
[[ "$(sha256sum build/weather-libraries/particle-rain-ff0e693a11a38fad454be72527ef605484e61c69.tar.gz | cut -d ' ' -f1)" == 68b0b8d318d42c34b934c8500dec5ddc4536c29bb70d6f5a91ca11f9e8309f97 ]]
[[ "$(sha256sum build/weather-libraries/immersive-storms-157767f062f464678f96af3c104def5c1f721ac3.tar.gz | cut -d ' ' -f1)" == 706ac420a2dfec3c535d988303d8c6df7eed91e502bd1f1b8294b71aded08107 ]]
# Explicit source allowlist: no .git, launch credentials, saves, profiles, logs,
# evidence screenshots or arbitrary home/workspace contents enter the bundle.
{ find src gradle tools -type f -print0
  find . -maxdepth 1 -type f -name '*.java' -print0
  printf '%s\0' build.gradle settings.gradle gradle.properties gradlew LICENSE LICENSE.md README.md THIRD-PARTY-NOTICES.md WEATHER-SOURCE-AND-RELINKING.md
} | sort -zu > "$output/source-files.list0"
hash_sources() {
  while IFS= read -r -d '' file; do
    [[ -f "$file" && ! -L "$file" ]] || { echo "Unsafe source input $file" >&2; return 1; }
    sha256sum "$file"
  done < "$output/source-files.list0"
}
hash_sources > "$output/source-inputs.sha256"
while IFS= read -r -d '' kv; do export "$kv"; done < /home/jan/.cache/simpleclouds/launch.env
cd "$project"
./gradlew --offline --no-daemon --max-workers=4 -PintegratedWeather=true -I tools/test-classpath.gradle build writeParityClasspath > "$output/build.log" 2>&1
hash_sources > "$output/source-inputs-after.sha256"
cmp -s "$output/source-inputs.sha256" "$output/source-inputs-after.sha256" || { echo "Source changed during build; do not release $output" >&2; exit 1; }
artifact=simple-clouds-0.7.3+26.3-fabric.jar
java -cp "$(cat build/parity-classpath.txt)" tools/VerifyReleaseArtifact.java "build/libs/$artifact" build/weather-libraries > "$output/artifact-check.log" 2>&1
cp -- "build/libs/$artifact" "build/libs/simple-clouds-0.7.3+26.3-fabric-sources.jar" "$output/"
cp -- build/weather-libraries/*.LICENSE "$output/upstream-source/"
cp -- build/weather-libraries/particle-rain-ff0e693a11a38fad454be72527ef605484e61c69.tar.gz build/weather-libraries/immersive-storms-157767f062f464678f96af3c104def5c1f721ac3.tar.gz "$output/upstream-source/"
cp -- THIRD-PARTY-NOTICES.md WEATHER-SOURCE-AND-RELINKING.md "$output/"
tar --create --gzip --file="$output/port-source.tar.gz" --sort=name --mtime='UTC 1970-01-01' --owner=0 --group=0 --numeric-owner --null --files-from="$output/source-files.list0"
(cd "$output"; sha256sum "$artifact" simple-clouds-0.7.3+26.3-fabric-sources.jar port-source.tar.gz upstream-source/* > SHA256SUMS)
echo "Private candidate package: $output"
echo 'Not installed/published. Runtime, clean-environment rebuild and final acceptance remain separate gates.'

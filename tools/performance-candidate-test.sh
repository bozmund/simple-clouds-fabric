#!/usr/bin/env bash
set -euo pipefail
cd /home/jan/simple-clouds-fabric
profile="$HOME/.local/share/ModrinthApp/profiles/Fabric 26.3"
config="$profile/config/simpleclouds-client.toml"
candidate=/tmp/simpleclouds-performance-config-20261001.toml
[[ -f "$config" && ! -L "$config" && -f "$candidate" && ! -L "$candidate" ]]
for pid in $(pgrep -x 'java|\.java-wrapped' 2>/dev/null || true); do
  if tr '\0' ' ' < "/proc/$pid/cmdline" | grep -q KnotClient; then
    echo "Refusing active Minecraft PID=$pid" >&2; exit 1
  fi
done
backup=$(mktemp -d /home/jan/.cache/simpleclouds/performance-config-test-20261001-XXXXXX)
cp -p "$config" "$backup/original.toml"
sha256sum "$config" > "$backup/original.sha256"
# Ensure the candidate only selects the existing five-frame static scheduler.
sed 's/generationInterval = "STATIC"/generationInterval = "TARGET_FPS"/' "$candidate" | cmp - "$config"
grep -qE 'framesToGenerateMesh = 5$' "$candidate"
restore() {
  result=$?; trap - EXIT
  cp -p "$backup/original.toml" "$config"
  cmp -s "$config" "$backup/original.toml" || result=1
  echo "Config restored; backup=$backup result=$result"
  exit "$result"
}
trap restore EXIT
cp -p "$candidate" "$config"
ORG_GRADLE_PROJECT_integratedWeather=true SIMPLECLOUDS_PROFILE=1 \
  bash tools/fullpack-coverage-test.sh RAINREPLAY

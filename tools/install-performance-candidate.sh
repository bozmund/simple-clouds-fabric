#!/usr/bin/env bash
set -euo pipefail
cd /home/jan/simple-clouds-fabric
profile="$HOME/.local/share/ModrinthApp/profiles/Fabric 26.3"
artifact=simple-clouds-0.7.3+26.3-fabric.jar
installed="$profile/mods/$artifact"
config="$profile/config/simpleclouds-client.toml"
candidate=/tmp/simpleclouds-performance-config-20261001.toml
expected=b1676cd704db0d50aca61eb744303ac666172487df39c01e00ada5f6a41ac396
previous=f1b4119a82216773eb43e81cce6d40e8a9dd88c13fe07bc06b2852afaea71ce2
for unit in simpleclouds-realgame simpleclouds-devclient simpleclouds-refclient; do
  if systemctl --user is-active --quiet "$unit"; then echo "Refusing active $unit"; exit 1; fi
done
for pid in $(pgrep -x 'java|\.java-wrapped' 2>/dev/null || true); do
  if tr '\0' ' ' < "/proc/$pid/cmdline" | grep -q KnotClient; then
    echo "Refusing active Minecraft PID=$pid" >&2; exit 1
  fi
done
[[ -f "$installed" && ! -L "$installed" && -f "$config" && ! -L "$config" ]]
[[ "$(sha256sum "build/libs/$artifact" | cut -d ' ' -f1)" == "$expected" ]]
[[ "$(sha256sum "$installed" | cut -d ' ' -f1)" == "$previous" ]]
sed 's/generationInterval = "STATIC"/generationInterval = "TARGET_FPS"/' "$candidate" | cmp - "$config"
backup=$(mktemp -d /home/jan/.cache/simpleclouds/performance-install-20261001-XXXXXX)
cp -p "$installed" "$backup/previous.jar"
cp -p "$config" "$backup/previous-client.toml"
cp -p "build/libs/$artifact" "$backup/installed.jar"
cp -p "$candidate" "$backup/installed-client.toml"
stage=$(mktemp "$profile/mods/.simpleclouds-performance-XXXXXX.tmp")
rollback() {
  result=$?; trap - EXIT
  if [[ "$result" != 0 ]]; then
    cp -p "$backup/previous.jar" "$installed"
    cp -p "$backup/previous-client.toml" "$config"
    echo "Installation failed; previous files restored from $backup" >&2
  fi
  [[ ! -f "$stage" ]] || rm -- "$stage"
  exit "$result"
}
trap rollback EXIT
cp -p "build/libs/$artifact" "$stage"
mv -- "$stage" "$installed"
cp -p "$candidate" "$config"
[[ "$(sha256sum "$installed" | cut -d ' ' -f1)" == "$expected" ]]
cmp "$config" "$candidate"
sha256sum "$installed" "$config"
echo "Installed performance candidate; backup=$backup"

#!/usr/bin/env bash
# Exact private candidate in the full scratch profile, without developer fixtures.
set -euo pipefail
cd /home/jan/simple-clouds-fabric
artifact=simple-clouds-0.7.3+26.3-fabric.jar
candidate=$(realpath -e "${1:?private candidate jar required}")
expected=${2:?expected SHA256 required}
[[ "$candidate" == "$PWD"/build/private-handoff-*/"$artifact" && -f "$candidate" && ! -L "$1" ]]
[[ "$expected" =~ ^[0-9a-f]{64}$ && "$(sha256sum "$candidate" | cut -d ' ' -f1)" == "$expected" ]]
profile='/home/jan/.local/share/ModrinthApp/profiles/Fabric 26.3'
installed="$profile/mods/$artifact"
for unit in simpleclouds-realgame simpleclouds-devclient simpleclouds-refclient simpleclouds-servercheck; do
  if systemctl --user is-active --quiet "$unit"; then echo "Concurrent owned runtime: $unit" >&2; exit 1; fi
done
for pid in $(pgrep -x 'java|\.java-wrapped' 2>/dev/null || true); do
  if tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | grep -qE 'KnotClient|cpw\.mods\.bootstraplauncher\.BootstrapLauncher|net\.minecraftforge\.userdev\.LaunchTesting'; then
    echo "Concurrent Minecraft PID=$pid" >&2; exit 1
  fi
done
[[ -f "$installed" && ! -L "$installed" && ! -L "$profile/devshot.request" && ! -L "$profile/options.txt" ]]
bash tools/test-memory-budget.sh check
evidence=$(mktemp -d "$PWD/evidence-codex-20261002-normal-indexed-XXXXXX")
mkdir "$evidence/before"
cp -p "$installed" "$evidence/before/profile.jar"
for file in devshot.request options.txt logs/latest.log; do
  [[ ! -f "$profile/$file" ]] || cp -p "$profile/$file" "$evidence/before/$(basename "$file")"
done
started=0
cleanup() {
  result=$?; trap - EXIT
  if [[ "$started" == 1 ]]; then
    systemctl --user show simpleclouds-realgame -p MemoryCurrent -p MemoryPeak -p ActiveState > "$evidence/unit-memory.log" || true
    systemctl --user stop simpleclouds-realgame || true
    [[ ! -f "$profile/logs/latest.log" ]] || cp -p "$profile/logs/latest.log" "$evidence/latest.log"
  fi
  if cmp -s "$candidate" "$installed"; then cp -p "$evidence/before/profile.jar" "$installed"
  else echo 'Concurrent installed JAR change preserved; original is in evidence/before/profile.jar' >&2; result=1; fi
  for file in devshot.request options.txt; do
    if [[ -f "$evidence/before/$file" ]]; then cp -p "$evidence/before/$file" "$profile/$file"
    elif [[ "$file" == devshot.request ]]; then rm -f -- "$profile/$file"; fi
  done
  [[ ! -f "$evidence/before/latest.log" ]] || cp -p "$evidence/before/latest.log" "$profile/logs/latest.log"
  printf 'Result=%s Evidence=%s\n' "$result" "$evidence"
  exit "$result"
}
trap cleanup EXIT
cp -p "$candidate" "$installed"
rm -f -- "$profile/devshot.request"
SIMPLECLOUDS_DEV=0 SIMPLECLOUDS_TEST_OIT_MRT=auto SC_WORLD=CodexFullPackTest SC_MEMORY_HIGH=7G SC_MEMORY_MAX=9G bash real-launch.sh > "$evidence/launch.log" 2>&1
started=1
for attempt in $(seq 1 90); do
  systemctl --user is-active --quiet simpleclouds-realgame || { echo 'Normal owned game ended unexpectedly' >&2; exit 1; }
  if [[ -f "$profile/logs/latest.log" ]] && grep -Eq 'Simple Clouds ERROR|cloud render pass failed|after-sky cloud pass failed|atmospheric sky pass failed|preview render pass failed|Failed to set up the 26.2 cloud pipeline|Failed to map buffer|Resource reload failed' "$profile/logs/latest.log"; then
    echo 'Normal launch port runtime gate failed' >&2; exit 1
  fi
  sleep 2
done
grep -q '\[OIT-BACKEND\] indexed=true capable=true developerOverride=none' "$profile/logs/latest.log"
grep -q '\[OIT-MRT\] original indexed RGBA16F/R8 attachments active; geometry drawn once' "$profile/logs/latest.log"
if grep -Eq '\[OIT-MRT-PARITY\]|\[NATIVE-RAIN-ROOF\]|\[DEVSHOT\] shooting|\[PREVIEW-PROBE\]|\[FULLPACK-WEATHER-LIFETIME\]' "$profile/logs/latest.log"; then
  echo 'Developer automation unexpectedly active in normal launch' >&2; exit 1
fi
echo 'PASS180second full-profile scratch launch, developer disabled, normal capability-selected MRT and no developer automation; direct offline launcher, not a Modrinth GUI launch'

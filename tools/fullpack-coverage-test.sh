#!/usr/bin/env bash
set -euo pipefail
cd /home/jan/simple-clouds-fabric
fixture=${1:-GRIDCROSS}
case "$fixture" in
  GRIDCROSS|GRIDSTORM|GRIDRAPID) expected_frames=81 ;;
  SHAKE) expected_frames=41 ;;
  *) echo 'Unknown coverage fixture' >&2; exit 2;;
esac
profile="$HOME/.local/share/ModrinthApp/profiles/Fabric 26.3"
artifact="simple-clouds-0.7.3+26.3-fabric.jar"
unit=simpleclouds-realgame
for other in simpleclouds-devclient simpleclouds-realgame simpleclouds-refclient; do
  if systemctl --user is-active --quiet "$other"; then echo "Refusing concurrent owned client: $other"; exit 1; fi
done
for pid in $(pgrep -x 'java|\.java-wrapped' 2>/dev/null || true); do
  if tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | grep -qE 'KnotClient|cpw\.mods\.bootstraplauncher\.BootstrapLauncher|net\.minecraftforge\.userdev\.LaunchTesting'; then
    echo "Refusing concurrent Minecraft PID $pid"; exit 1
  fi
done
bash tools/test-memory-budget.sh check
evidence=$(mktemp -d "$PWD/evidence-codex-20260930-fullpack-coverage-${fixture}-XXXXXX")
mkdir -p "$evidence/before/screenshots" "$evidence/test/screenshots"
cp -p "$profile/mods/$artifact" "$evidence/before/profile.jar"
sha256sum "$evidence/before/profile.jar" > "$evidence/before/profile.sha256"
for file in "$profile/screenshots/"devshot*.png; do
  [[ -f "$file" ]] && cp -p "$file" "$evidence/before/screenshots/"
done
for file in "$profile/devshot.request" "$profile/logs/latest.log"; do
  [[ -f "$file" ]] && cp -p "$file" "$evidence/before/"
done
launched=0
game_started=0
cleanup() {
  result=$?; trap - EXIT
  if [[ "$launched" == 1 ]]; then
    systemctl --user show "$unit" -p MemoryCurrent -p MemoryPeak -p ActiveState > "$evidence/unit-memory.log" || true
    cat /proc/pressure/memory > "$evidence/memory-pressure.log"
    systemctl --user stop "$unit" || true
    if [[ "$game_started" == 1 && -f "$profile/logs/latest.log" ]]; then
      cp -p "$profile/logs/latest.log" "$evidence/test/latest.log"
    fi
    for file in "$profile/screenshots/"devshot*.png; do
      [[ -f "$file" ]] && cp -p "$file" "$evidence/test/screenshots/"
    done
  fi
  cp -p "$evidence/before/profile.jar" "$profile/mods/$artifact"
  for file in "$profile/screenshots/"devshot*.png; do [[ -f "$file" ]] && rm -- "$file"; done
  for file in "$evidence/before/screenshots/"*.png; do
    [[ -f "$file" ]] && cp -p "$file" "$profile/screenshots/"
  done
  if [[ -f "$evidence/before/devshot.request" ]]; then
    cp -p "$evidence/before/devshot.request" "$profile/devshot.request"
  else rm -f -- "$profile/devshot.request"; fi
  [[ -f "$evidence/before/latest.log" ]] && cp -p "$evidence/before/latest.log" "$profile/logs/latest.log"
  sha256sum "$profile/mods/$artifact" > "$evidence/restored-profile.sha256"
  printf 'Result=%s Evidence=%s\n' "$result" "$evidence"
  exit "$result"
}
trap cleanup EXIT
(while IFS= read -r -d '' kv; do export "$kv"; done < "$HOME/.cache/simpleclouds/launch.env"
 ./gradlew --offline --no-daemon --max-workers=8 build) > "$evidence/build.log" 2>&1
cp -p "build/libs/$artifact" "$profile/mods/$artifact"
sha256sum "$profile/mods/$artifact" > "$evidence/candidate.sha256"
for file in "$profile/screenshots/"devshot*.png; do [[ -f "$file" ]] && rm -- "$file"; done
launched=1
SIMPLECLOUDS_DEV=1 SIMPLECLOUDS_TRACE_VISUAL_CHURN=1 SC_MEMORY_HIGH=7G SC_MEMORY_MAX=9G \
  bash real-launch.sh "240 $fixture" > "$evidence/launcher.log" 2>&1
game_started=1
for ((attempt=0;attempt<90;attempt++)); do
  count=$(find "$profile/screenshots" -maxdepth 1 -type f -name 'devshot-SHAKE-*.png' | wc -l)
  printf 'frames=%s\n' "$count" >> "$evidence/progress.log"
  if [[ "$count" -eq "$expected_frames" ]]; then
    if grep -Eq 'Simple Clouds ERROR|Failed to map buffer|failed=[1-9][0-9]*|world-cell clip cap exceeded' "$profile/logs/latest.log"; then
      echo 'Runtime error gate failed' >&2; exit 1
    fi
    if [[ "${SIMPLECLOUDS_GPU_TEST_CPU_FALLBACK:-0}" == 1 ]]; then
      # A completed capture alone cannot prove that the injection happened or
      # that subsequent CPU work ran. Wait for both explicit runtime evidence.
      if ! awk -f tools/verify-gpu-fallback.awk "$profile/logs/latest.log" >/dev/null; then
        sleep 2
        continue
      fi
    fi
    echo "Captured all $expected_frames frames; runtime error gate passed; visual review still required"
    exit 0
  fi
  if ! systemctl --user is-active --quiet "$unit"; then echo 'Owned game terminated before capture' >&2; exit 1; fi
  sleep 2
done
echo 'Capture timed out' >&2
exit 1

#!/usr/bin/env bash
set -euo pipefail
cd /home/jan/simple-clouds-fabric
evidence=$(mktemp -d "$PWD/evidence-codex-20260930-clip-smoke-XXXXXX")
mkdir -p "$evidence/before/screenshots" "$evidence/test/screenshots"
for file in run/screenshots/devshot*.png; do
  [[ -f "$file" ]] && cp -p "$file" "$evidence/before/screenshots/"
done
for file in run/devshot.request run/logs/latest.log; do
  [[ -f "$file" ]] && cp -p "$file" "$evidence/before/"
done
cleanup() {
  result=$?
  trap - EXIT
  systemctl --user stop simpleclouds-devclient || true
  for file in run/screenshots/devshot*.png; do
    [[ -f "$file" ]] && cp -p "$file" "$evidence/test/screenshots/"
  done
  [[ -f run/logs/latest.log ]] && cp -p run/logs/latest.log "$evidence/test/latest.log"
  # The launcher creates only these test-named images; every pre-existing one
  # was saved above. Never remove normal player screenshots.
  for file in run/screenshots/devshot*.png; do [[ -f "$file" ]] && rm -- "$file"; done
  for file in "$evidence/before/screenshots/"*.png; do
    [[ -f "$file" ]] && cp -p "$file" run/screenshots/
  done
  if [[ -f "$evidence/before/devshot.request" ]]; then
    cp -p "$evidence/before/devshot.request" run/devshot.request
  else rm -f -- run/devshot.request; fi
  [[ -f "$evidence/before/latest.log" ]] && cp -p "$evidence/before/latest.log" run/logs/latest.log
  printf 'Evidence: %s\n' "$evidence"
  exit "$result"
}
trap cleanup EXIT
# Keep the successful client only until these read-only counters are captured;
# the enclosing EXIT trap still stops it before returning, including failures.
DEV_KEEP_RUNNING=1 bash dev-relaunch.sh > "$evidence/launcher.log" 2>&1
systemctl --user show simpleclouds-devclient -p MemoryPeak -p MemoryCurrent -p ActiveState > "$evidence/unit-memory.log"
cat /proc/pressure/memory > "$evidence/memory-pressure.log"

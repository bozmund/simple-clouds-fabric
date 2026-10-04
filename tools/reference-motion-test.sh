#!/usr/bin/env bash
set -euo pipefail
cd /home/jan/simple-clouds-fabric
reference=/home/jan/.cache/simpleclouds/original-1.20.1
fixture=${1:-SHAKE}
case "$fixture" in SHAKE|MATCHSHAKE|LONGSHAKE|LONGMATCHSHAKE) ;; *) echo 'Unknown reference fixture' >&2; exit 2;; esac
expected_frames=41
if [[ "$fixture" == LONGSHAKE || "$fixture" == LONGMATCHSHAKE ]]; then expected_frames=121; fi
unit=simpleclouds-refclient
for other in simpleclouds-devclient simpleclouds-realgame simpleclouds-refclient; do
  if systemctl --user is-active --quiet "$other"; then echo "Concurrent owned client: $other" >&2; exit 1; fi
done
for pid in $(pgrep -x 'java|\.java-wrapped' 2>/dev/null || true); do
  if tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | grep -qE 'KnotClient|cpw\.mods\.bootstraplauncher\.BootstrapLauncher|net\.minecraftforge\.userdev\.LaunchTesting'; then
    echo "Concurrent Minecraft PID $pid" >&2; exit 1
  fi
done
bash tools/test-memory-budget.sh check
evidence=$(mktemp -d "$PWD/evidence-codex-20260930-reference-motion-XXXXXX")
mkdir -p "$evidence/before/screenshots" "$evidence/test/screenshots"
for file in "$reference/run/screenshots/"devshot-*.png; do
  [[ -f "$file" ]] && cp -p "$file" "$evidence/before/screenshots/"
done
for name in devshot.request logs/latest.log options.txt; do
  [[ -f "$reference/run/$name" ]] && cp -p "$reference/run/$name" "$evidence/before/"
done
started=0
cleanup() {
  result=$?
  trap - EXIT
  if [[ "$started" == 1 ]]; then
    systemctl --user show "$unit" -p MemoryCurrent -p MemoryPeak -p ActiveState > "$evidence/unit-memory.log" || true
    systemctl --user stop "$unit" || true
    [[ -f "$reference/run/logs/latest.log" ]] && cp -p "$reference/run/logs/latest.log" "$evidence/test/latest.log"
    for file in "$reference/run/screenshots/"devshot-*.png; do
      [[ -f "$file" ]] && cp -p "$file" "$evidence/test/screenshots/"
    done
  fi
  for file in "$reference/run/screenshots/"devshot-*.png; do [[ -f "$file" ]] && rm -- "$file"; done
  for file in "$evidence/before/screenshots/"*.png; do [[ -f "$file" ]] && cp -p "$file" "$reference/run/screenshots/"; done
  if [[ -f "$evidence/before/devshot.request" ]]; then cp -p "$evidence/before/devshot.request" "$reference/run/devshot.request"; else rm -f -- "$reference/run/devshot.request"; fi
  [[ -f "$evidence/before/latest.log" ]] && cp -p "$evidence/before/latest.log" "$reference/run/logs/latest.log"
  [[ -f "$evidence/before/options.txt" ]] && cp -p "$evidence/before/options.txt" "$reference/run/options.txt"
  printf 'Result=%s Evidence=%s\n' "$result" "$evidence"
  exit "$result"
}
trap cleanup EXIT
for file in "$reference/run/screenshots/"devshot-*.png; do [[ -f "$file" ]] && rm -- "$file"; done
# Generated request, not a source edit. Use an existing disposable reference
# world; no profile, package, shader, cloud settings or source is modified.
printf '240 CREATEFLAT CodexRefHighMotion20260929 20260929 %s\n' "$fixture" > "$reference/run/devshot.request"
systemd-run --user --unit="$unit" --collect --quiet --expand-environment=no \
  --property="WorkingDirectory=$reference" --property=MemoryHigh=7G --property=MemoryMax=9G \
  --property=TimeoutStopSec=8s --property=KillMode=control-group \
  --property="Environment=SIMPLECLOUDS_PROFILE=${SIMPLECLOUDS_PROFILE:-0}" \
  bash -c '
    guard=/home/jan/simple-clouds-fabric/tools/test-memory-budget.sh
    bash "$guard" watch simpleclouds-refclient & monitor=$!
    trap "kill $monitor 2>/dev/null || true" EXIT
    live_display=${DISPLAY-}; live_auth=${XAUTHORITY-}
    live_wayland=${WAYLAND_DISPLAY-}; live_runtime=${XDG_RUNTIME_DIR-}
    while IFS= read -r -d "" kv; do export "$kv"; done < /home/jan/.cache/simpleclouds/launch.env
    export DISPLAY="$live_display" XAUTHORITY="$live_auth" WAYLAND_DISPLAY="$live_wayland" XDG_RUNTIME_DIR="$live_runtime"
    unset JAVA_HOME
    nix shell --impure --expr '\''(builtins.getFlake "path:/home/jan/nixos-symbiote").inputs.nixpkgs.legacyPackages.x86_64-linux.jdk17'\'' \
      -c bash -c '\''export JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$(command -v java)")")"); ./gradlew --offline --no-daemon --max-workers=4 runClient --console=plain'\''
  ' > "$evidence/launcher.log" 2>&1
started=1
for i in $(seq 1 120); do
  count=$(find "$reference/run/screenshots" -maxdepth 1 -name 'devshot-SHAKE-*.png' -type f | wc -l)
  printf 'frames=%s\n' "$count" >> "$evidence/progress.log"
  if (( count >= expected_frames )); then
    if grep -Eq '\[REFDEV\] harness failed|Resource reload failed' "$reference/run/logs/latest.log"; then
      echo 'Reference runtime error gate failed' >&2; exit 1
    fi
    echo "Reference captured $expected_frames frames; visual review required"; exit 0
  fi
  if ! systemctl --user is-active --quiet "$unit"; then
    journalctl --user -u "$unit" -n 35 --no-pager > "$evidence/unit-failure.log"
    echo 'Reference client terminated before capture' >&2; exit 1
  fi
  sleep 2
done
echo 'Reference capture timed out' >&2
exit 1

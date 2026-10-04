#!/usr/bin/env bash
# Diagnostic only: reproduce DH/Iris with the exact user pack minus Simple Clouds.
# A reproduced error is an observation, NEVER a passing compatibility result.
set -euo pipefail
cd /home/jan/simple-clouds-fabric
profile="$HOME/.local/share/ModrinthApp/profiles/Fabric 26.3"
artifact="$profile/mods/simple-clouds-0.7.3+26.3-fabric.jar"
iris="$profile/config/iris.properties"
pack=${SIMPLECLOUDS_TEST_SHADER_PACK:-miniature-shader-2.19.zip}
case "$pack" in
  miniature-shader-2.19.zip|ComplementaryReimagined_r5.9.3.zip|ComplementaryUnbound_r5.9.3.zip) ;;
  *) echo 'Unknown baseline shader pack' >&2; exit 2 ;;
esac
unit=simpleclouds-realgame
for other in simpleclouds-devclient simpleclouds-realgame simpleclouds-refclient; do
  if systemctl --user is-active --quiet "$other"; then echo "Concurrent owned client: $other" >&2; exit 1; fi
done
for pid in $(pgrep -x 'java|\.java-wrapped' 2>/dev/null || true); do
  if tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | grep -qE 'KnotClient|cpw\.mods\.bootstraplauncher\.BootstrapLauncher|net\.minecraftforge\.userdev\.LaunchTesting'; then
    echo "Concurrent Minecraft PID $pid" >&2; exit 1
  fi
done
for file in "$artifact" "$iris" "$profile/shaderpacks/$pack" "$profile/mods/DistantHorizons-3.3.4-26.3-fabric-neoforge.jar"; do
  [[ -f "$file" && ! -L "$file" ]] || { echo "Missing/unsafe baseline input: $file" >&2; exit 1; }
done
grep -q '^enableShaders=' "$iris" && grep -q '^shaderPack=' "$iris"
[[ -f "$profile/saves/CodexFullPackTest/level.dat" ]] || { echo 'Missing exact scratch save' >&2; exit 1; }
bash tools/test-memory-budget.sh check
evidence=$(mktemp -d "$PWD/evidence-codex-20261001-no-clouds-baseline-XXXXXX")
mkdir -p "$evidence/before" "$evidence/test"
cp -p "$iris" "$evidence/before/iris.properties"
for file in options.txt devshot.request logs/latest.log; do
  [[ -f "$profile/$file" ]] && cp -p "$profile/$file" "$evidence/before/"
done
sha256sum "$artifact" > "$evidence/before/profile.sha256"
sha256sum "$profile/mods/DistantHorizons-3.3.4-26.3-fabric-neoforge.jar" "$profile/mods/iris-fabric-1.11.6+mc26.3.jar" > "$evidence/input-mods.sha256"
moved=0
launched=0
cleanup() {
  result=$?; trap - EXIT
  if [[ "$launched" == 1 ]]; then
    systemctl --user stop "$unit" || true
    [[ -f "$profile/logs/latest.log" ]] && cp -p "$profile/logs/latest.log" "$evidence/test/latest.log"
    journalctl --user -u "$unit" --since "@$started_at" --no-pager > "$evidence/service-journal.log" || true
  fi
  if [[ "$moved" == 1 ]]; then
    if [[ -e "$artifact" ]]; then
      echo "Refusing to overwrite replacement; original retained at $evidence/before/profile.jar" >&2; result=1
    else
      mv -- "$evidence/before/profile.jar" "$artifact"
      sha256sum "$artifact" > "$evidence/restored-profile.sha256"
      [[ "$(cut -d ' ' -f1 "$evidence/restored-profile.sha256")" == "$(cut -d ' ' -f1 "$evidence/before/profile.sha256")" ]] || result=1
    fi
  fi
  cp -p "$evidence/before/iris.properties" "$iris"
  for file in options.txt latest.log; do
    [[ -f "$evidence/before/$file" ]] || continue
    target="$profile/$file"; [[ "$file" == latest.log ]] && target="$profile/logs/latest.log"
    cp -p "$evidence/before/$file" "$target"
  done
  if [[ -f "$evidence/before/devshot.request" ]]; then cp -p "$evidence/before/devshot.request" "$profile/devshot.request"; else rm -f -- "$profile/devshot.request"; fi
  cmp -s "$iris" "$evidence/before/iris.properties" || result=1
  echo "Result=$result Evidence=$evidence"
  exit "$result"
}
trap cleanup EXIT
trap 'exit 143' TERM
trap 'exit 130' INT
mv -- "$artifact" "$evidence/before/profile.jar"
moved=1
sed -i -e 's/^enableShaders=.*/enableShaders=true/' -e "s/^shaderPack=.*/shaderPack=$pack/" "$iris"
started_at=$(date +%s)
launched=1
SIMPLECLOUDS_DEV=0 SC_WORLD=CodexFullPackTest SC_MEMORY_HIGH=8G SC_MEMORY_MAX=9G bash real-launch.sh > "$evidence/launcher.log" 2>&1
for ((attempt=0;attempt<60;attempt++)); do
  log="$profile/logs/latest.log"
  if [[ -f "$log" && "$(stat -c %Y "$log")" -ge "$started_at" ]]; then
    if grep -q 'Close the existing render pass before performing additional commands' "$log"; then
      printf '%s\n' 'REPRODUCED render-pass error without Simple Clouds; inspect exact stack before attribution.' > "$evidence/outcome.txt"
      cat "$evidence/outcome.txt"; exit 0
    fi
    if grep -Eq 'Mod resolution encountered|Incompatible mod set|Failed to start the minecraft server|Failed to load registries' "$log"; then
      echo 'Baseline launch/configuration failed; cannot attribute rendering error' >&2; exit 1
    fi
  fi
  if ! systemctl --user is-active --quiet "$unit"; then echo 'Baseline client stopped before observation' >&2; exit 1; fi
  sleep 2
done
printf '%s\n' 'NOT REPRODUCED within 120 seconds; this is inconclusive, not compatibility acceptance.' > "$evidence/outcome.txt"
cat "$evidence/outcome.txt"
exit 3

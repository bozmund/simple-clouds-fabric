#!/usr/bin/env bash
set -euo pipefail
cd /home/jan/simple-clouds-fabric
fixture=${1:-GRIDCROSS}
if [[ "${SIMPLECLOUDS_TEST_COUNTER_RELOAD:-0}" == 1 && "${SIMPLECLOUDS_TEST_COUNTER_SNAPSHOT:-0}" != 1 ]]; then
  echo 'Counter resource reload requires explicit snapshot fixture opt-in' >&2; exit 2
fi
baseline_args=(-I tools/mesh-baseline.gradle)
case "${SIMPLECLOUDS_TEST_ALLOCATION_BASELINE:-0}" in
  0) ;;
  1) baseline_args=(-I tools/mesh-baseline.gradle) ;;
  *) echo 'Invalid mesh baseline diagnostic flag' >&2; exit 2 ;;
esac
request_fixture=$fixture
case "${SIMPLECLOUDS_TEST_OIT_MRT:-auto}" in 0|1|auto) ;; *) echo 'Invalid indexed OIT backend override' >&2; exit 2;; esac
case "${SIMPLECLOUDS_TEST_OIT_MRT_PARITY:-0}" in 0|1) ;; *) echo 'Invalid indexed OIT parity flag' >&2; exit 2;; esac
if [[ "${SIMPLECLOUDS_TEST_OIT_MRT_PARITY:-0}" == 1 && "${SIMPLECLOUDS_TEST_OIT_MRT:-0}" != 1 ]]; then
  echo 'Indexed OIT parity requires indexed OIT candidate' >&2; exit 2
fi
test_weather_lifetime=${SIMPLECLOUDS_TEST_FULLPACK_WEATHER_LIFECYCLE:-0}
test_native_roof=${SIMPLECLOUDS_TEST_NATIVE_RAIN_ROOF:-0}
case "$test_native_roof" in 0|1) ;; *) echo 'Invalid native rain roof flag' >&2; exit 2;; esac
if [[ "$test_native_roof" == 1 && ( "$fixture" != ROOF || "${SC_WORLD:-}" != CodexGlassRoof20261001 || "${SIMPLECLOUDS_TEST_INTEGRATED_WEATHER:-0}" != 1 ) ]]; then
  echo 'Native rain roof requires integrated weather and exact fullpack glass-roof fixture' >&2; exit 2
fi
restore_client_config=0
[[ "$test_weather_lifetime" == 1 || "$test_native_roof" == 1 ]] && restore_client_config=1
test_weather_sustained=${SIMPLECLOUDS_TEST_WEATHER_SUSTAINED:-0}
case "$test_weather_sustained" in 0|1) ;; *) echo 'Invalid sustained weather flag' >&2; exit 2;; esac
if [[ "$test_weather_sustained" == 1 && "$test_weather_lifetime" != 1 ]]; then
  echo 'Sustained weather requires full-pack lifecycle fixture' >&2; exit 2
fi
residence_ticks=400
[[ "$test_weather_sustained" == 1 ]] && residence_ticks=6000
case "$test_weather_lifetime" in 0|1) ;; *) echo 'Invalid weather lifecycle flag' >&2; exit 2;; esac
if [[ "$test_weather_lifetime" == 1 && ( "$fixture" != RAINREPLAY || "${SIMPLECLOUDS_TEST_INTEGRATED_WEATHER:-0}" != 1 ) ]]; then
  echo 'Full-pack weather lifecycle requires integrated weather and RAINREPLAY scratch fixture' >&2; exit 2
fi
case "${SIMPLECLOUDS_TEST_INTEGRATED_WEATHER:-0}" in
  0) baseline_args+=(-PintegratedWeather=false) ;;
  1) baseline_args+=(-PintegratedWeather=true) ;;
  *) echo 'Invalid integrated weather test flag' >&2; exit 2 ;;
esac
if [[ ( "${SIMPLECLOUDS_TEST_DH_FOG_LIFECYCLE:-0}" == 1 || "${SIMPLECLOUDS_TEST_NATIVE_BIOME_WEATHER:-0}" == 1 || "${SIMPLECLOUDS_TEST_WEATHER_LIBRARIES:-0}" == 1 ) && "${SIMPLECLOUDS_TEST_INTEGRATED_WEATHER:-0}" != 1 ]]; then
  echo 'Native weather fixtures require integrated weather build' >&2; exit 2
fi
case "$fixture" in
  GRIDCROSS|GRIDSTORM|GRIDRAPID|RAINREPLAY) expected_frames=81 ;;
  GRIDSTORMNIGHT) expected_frames=81; request_fixture='GRIDSTORM NIGHT' ;;
  ROOF) expected_frames=81; request_fixture=GRIDSTORM ;;
  SHAKE|MATCHSHAKE) expected_frames=41 ;;
  BOLTFAR) expected_frames=24; frame_glob='devshot-BF-*.png' ;;
  BOLTFARNODH) expected_frames=24; frame_glob='devshot-BF-*.png'; request_fixture='BOLTFAR NODHBOLT' ;;
  JANFP) expected_frames=6; frame_glob='devshot-JAN-*.png'; request_fixture="CAM:${SC_CAM:?SC_CAM=x,y,z,pitch,yaw required}"; restore_client_config=1 ;;
  STORMFP) expected_frames=64; frame_glob='devshot-S*.png'; request_fixture='STORM' ;;
  STORMFPNIGHT) expected_frames=64; frame_glob='devshot-S*.png'; request_fixture='STORM NIGHT' ;;
  ABOVEFP) expected_frames=2; frame_glob='devshot-[DF].png'; request_fixture='D F' ;;
  ABOVEFPNIGHT) expected_frames=2; frame_glob='devshot-[DF].png'; request_fixture='D F NIGHT' ;;
  MATCHSHAKENIGHT) expected_frames=41; request_fixture='MATCHSHAKE NIGHT' ;;
  *) echo 'Unknown coverage fixture' >&2; exit 2;;
esac
profile="$HOME/.local/share/ModrinthApp/profiles/Fabric 26.3"
if [[ "$restore_client_config" == 1 ]]; then
  [[ -f "$profile/config/simpleclouds-client.toml" && ! -L "$profile/config/simpleclouds-client.toml" ]] || { echo 'Missing/unsafe client config for lifecycle restoration' >&2; exit 2; }
fi
roof_test=0
if [[ "$fixture" == ROOF ]]; then
  case "${SC_WORLD:-}" in CodexRoof20261001|CodexGlassRoof20261001) ;; *) echo 'Roof test requires exact copied scratch world' >&2; exit 2;; esac
  [[ -f "$profile/saves/$SC_WORLD/level.dat" ]] || { echo 'Roof scratch save missing' >&2; exit 1; }
  roof_test=1
fi
artifact="simple-clouds-0.7.3+26.3-fabric.jar"
unit=simpleclouds-realgame
isolate_dh=${SIMPLECLOUDS_TEST_WITHOUT_DH:-0}
dh_jar="$profile/mods/DistantHorizons-3.3.4-26.3-fabric-neoforge.jar"
if [[ "$isolate_dh" == 1 ]]; then
  [[ -f "$dh_jar" && ! -L "$dh_jar" ]] || { echo 'Missing/unsafe exact DH diagnostic JAR' >&2; exit 1; }
elif [[ "$isolate_dh" != 0 ]]; then echo 'Invalid DH diagnostic flag' >&2; exit 2; fi
shader_pack=${SIMPLECLOUDS_TEST_SHADER_PACK:-}
test_transparency=${SIMPLECLOUDS_TEST_IMPROVED_TRANSPARENCY:-}
test_uncapped=${SIMPLECLOUDS_TEST_UNCAPPED:-0}
case "$test_uncapped" in
  0) ;;
  1)
    [[ -f "$profile/options.txt" && ! -L "$profile/options.txt" ]] || { echo 'Unsafe uncapped test options' >&2; exit 2; }
    grep -qE '^enableVsync:(true|false)$' "$profile/options.txt" && grep -qE '^maxFps:[0-9]+$' "$profile/options.txt" || { echo 'Unknown FPS options format' >&2; exit 2; }
    ;;
  *) echo 'Invalid uncapped diagnostic flag' >&2; exit 2 ;;
esac
if [[ -n "$test_transparency" ]]; then
  case "$test_transparency" in 0|1) ;; *) echo 'Invalid transparency diagnostic flag' >&2; exit 2;; esac
  [[ -f "$profile/options.txt" && ! -L "$profile/options.txt" ]] && grep -qE '^improvedTransparency:(true|false)$' "$profile/options.txt" || { echo 'Missing/unsafe transparency test option' >&2; exit 1; }
fi
if [[ -n "$shader_pack" ]]; then
  case "$shader_pack" in miniature-shader-2.19.zip|ComplementaryReimagined_r5.9.3.zip|ComplementaryUnbound_r5.9.3.zip) ;; *) echo 'Unknown shader test pack' >&2; exit 2;; esac
  [[ -f "$profile/shaderpacks/$shader_pack" && -f "$profile/config/iris.properties" && ! -L "$profile/config/iris.properties" ]] || { echo 'Missing/unsafe Iris test inputs' >&2; exit 1; }
  grep -q '^enableShaders=' "$profile/config/iris.properties" && grep -q '^shaderPack=' "$profile/config/iris.properties" || { echo 'Unexpected Iris config format' >&2; exit 1; }
fi
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
for file in "$profile/devshot.request" "$profile/logs/latest.log" "$profile/options.txt"; do
  [[ -f "$file" ]] && cp -p "$file" "$evidence/before/"
done
launched=0
if [[ "$restore_client_config" == 1 ]]; then
  cp -p "$profile/config/simpleclouds-client.toml" "$evidence/before/simpleclouds-client.toml"
fi
game_started=0
dh_moved=0
mod_moved=0
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
  if [[ "$dh_moved" == 1 ]]; then
    if [[ -e "$dh_jar" ]]; then
      echo "Refusing to overwrite replacement DH JAR; original preserved at $evidence/before/dh.jar" >&2
      result=1
    else
      mv -- "$evidence/before/dh.jar" "$dh_jar"
      sha256sum "$dh_jar" > "$evidence/restored-dh.sha256"
      [[ "$(cut -d ' ' -f1 "$evidence/before/dh.sha256")" == "$(cut -d ' ' -f1 "$evidence/restored-dh.sha256")" ]] || result=1
    fi
  fi
  if [[ "$mod_moved" == 1 ]]; then
    if [[ -e "$profile/mods/$SC_ISOLATE_MOD" ]]; then
      echo "Refusing to overwrite isolated mod; original preserved at $evidence/before/isolated-mod.jar" >&2
      result=1
    else
      mv -- "$evidence/before/isolated-mod.jar" "$profile/mods/$SC_ISOLATE_MOD"
      sha256sum "$profile/mods/$SC_ISOLATE_MOD" > "$evidence/restored-isolated-mod.sha256"
      [[ "$(cut -d ' ' -f1 "$evidence/before/isolated-mod.sha256")" == "$(cut -d ' ' -f1 "$evidence/restored-isolated-mod.sha256")" ]] || result=1
    fi
  fi
  for file in "$profile/screenshots/"devshot*.png; do [[ -f "$file" ]] && rm -- "$file"; done
  for file in "$evidence/before/screenshots/"*.png; do
    [[ -f "$file" ]] && cp -p "$file" "$profile/screenshots/"
  done
  if [[ -f "$evidence/before/devshot.request" ]]; then
    cp -p "$evidence/before/devshot.request" "$profile/devshot.request"
  else rm -f -- "$profile/devshot.request"; fi
  [[ -f "$evidence/before/latest.log" ]] && cp -p "$evidence/before/latest.log" "$profile/logs/latest.log"
  [[ -f "$evidence/before/options.txt" ]] && cp -p "$evidence/before/options.txt" "$profile/options.txt"
  if [[ -n "$test_transparency" || "$test_uncapped" == 1 ]] && ! cmp -s "$evidence/before/options.txt" "$profile/options.txt"; then
    echo 'Failed to restore exact options after transparency test' >&2; result=1
  fi
  if [[ -f "$evidence/before/iris.properties" ]]; then
    cp -p "$profile/config/iris.properties" "$evidence/test/iris.properties"
    cp -p "$evidence/before/iris.properties" "$profile/config/iris.properties"
    cmp -s "$evidence/before/iris.properties" "$profile/config/iris.properties" || result=1
  fi
  sha256sum "$profile/mods/$artifact" > "$evidence/restored-profile.sha256"
  if [[ "$restore_client_config" == 1 ]]; then
    cp -p "$profile/config/simpleclouds-client.toml" "$evidence/test/simpleclouds-client.toml"
    cp -p "$evidence/before/simpleclouds-client.toml" "$profile/config/simpleclouds-client.toml"
    cmp -s "$evidence/before/simpleclouds-client.toml" "$profile/config/simpleclouds-client.toml" || result=1
  fi
  printf 'Result=%s Evidence=%s\n' "$result" "$evidence"
  exit "$result"
}
trap cleanup EXIT
if [[ "$test_uncapped" == 1 ]]; then
  sed -i -e 's/^enableVsync:.*/enableVsync:false/' -e 's/^maxFps:.*/maxFps:260/' "$profile/options.txt"
fi
cp -p "$profile/options.txt" "$evidence/test/options.txt"
if [[ -n "$test_transparency" ]]; then
  value=false; [[ "$test_transparency" == 1 ]] && value=true
  sed -i "s/^improvedTransparency:.*/improvedTransparency:$value/" "$profile/options.txt"
fi
if [[ "$isolate_dh" == 1 ]]; then
  sha256sum "$dh_jar" > "$evidence/before/dh.sha256"
  mv -- "$dh_jar" "$evidence/before/dh.jar"
  dh_moved=1
fi
# SC_ISOLATE_MOD=<jar file name in mods/>: run without that one mod (restored on exit).
if [[ -n "${SC_ISOLATE_MOD:-}" ]]; then
  [[ "$SC_ISOLATE_MOD" != */* && -f "$profile/mods/$SC_ISOLATE_MOD" && "$SC_ISOLATE_MOD" != "$artifact" ]]     || { echo "Invalid SC_ISOLATE_MOD" >&2; exit 2; }
  sha256sum "$profile/mods/$SC_ISOLATE_MOD" > "$evidence/before/isolated-mod.sha256"
  mv -- "$profile/mods/$SC_ISOLATE_MOD" "$evidence/before/isolated-mod.jar"
  mod_moved=1
fi
if [[ -n "$shader_pack" ]]; then
  cp -p "$profile/config/iris.properties" "$evidence/before/iris.properties"
  sed -i -e 's/^enableShaders=.*/enableShaders=true/' -e "s/^shaderPack=.*/shaderPack=$shader_pack/" "$profile/config/iris.properties"
fi
(while IFS= read -r -d '' kv; do export "$kv"; done < "$HOME/.cache/simpleclouds/launch.env"
 cd /home/jan/simple-clouds-fabric
 ./gradlew --offline --no-daemon --max-workers=8 "${baseline_args[@]}" build) > "$evidence/build.log" 2>&1
cp -p "build/libs/$artifact" "$profile/mods/$artifact"
sha256sum "$profile/mods/$artifact" > "$evidence/candidate.sha256"
for file in "$profile/screenshots/"devshot*.png; do [[ -f "$file" ]] && rm -- "$file"; done
launched=1
SIMPLECLOUDS_DEV=1 SIMPLECLOUDS_TEST_ROOF=$roof_test SIMPLECLOUDS_TRACE_VISUAL_CHURN=1 SC_MEMORY_HIGH=7G SC_MEMORY_MAX=9G \
  bash real-launch.sh "${SC_SETTLE_FRAMES:-240} ${SC_EXTRA_TOKENS:+$SC_EXTRA_TOKENS }$request_fixture" > "$evidence/launcher.log" 2>&1
game_started=1
runtime_errors='Simple Clouds ERROR|Simple Clouds: cloud render pass failed|after-sky cloud pass failed|atmospheric sky pass failed|Failed to set up the 26.2 cloud pipeline|Failed to map buffer|failed=[1-9][0-9]*|world-cell clip cap exceeded|Close the existing render pass before performing additional commands|Shader compilation failed'
attempt_limit=90
[[ "$test_weather_lifetime" == 1 ]] && attempt_limit=180
[[ "$test_weather_sustained" == 1 ]] && attempt_limit=420
[[ "${SC_ATTEMPT_LIMIT:-}" =~ ^[0-9]+$ ]] && attempt_limit=$SC_ATTEMPT_LIMIT
for ((attempt=0;attempt<attempt_limit;attempt++)); do
  if grep -Eq "$runtime_errors" "$profile/logs/latest.log"; then
    echo 'Runtime error gate failed before capture; inspect preserved game log' >&2; exit 1
  fi
  count=$(find "$profile/screenshots" -maxdepth 1 -type f -name "${frame_glob:-devshot-SHAKE-*.png}" | wc -l)
  printf 'frames=%s\n' "$count" >> "$evidence/progress.log"
  if [[ "$count" -eq "$expected_frames" ]]; then
    if [[ "${SIMPLECLOUDS_TEST_OIT_MRT_PARITY:-0}" == 1 ]] && ! grep -q '\[OIT-MRT-PARITY\] PASS 16 actual full-field attachment pairs and backend blend-state restoration' "$profile/logs/latest.log"; then
      if ! systemctl --user is-active --quiet "$unit"; then echo 'Owned game ended before OIT parity completion' >&2; exit 1; fi
      sleep 2
      continue
    fi
    if [[ "$test_weather_lifetime" == 1 ]] && ! grep -q "\[FULLPACK-WEATHER-LIFETIME\] PASS actual Nether/back/two save-unloads/reopen/${residence_ticks}tick bounded residence/AsyncParticles counter/history/DH lease cleanup" "$profile/logs/latest.log"; then
      if ! systemctl --user is-active --quiet "$unit"; then echo 'Owned game terminated before lifecycle completion' >&2; exit 1; fi
      sleep 2
      continue
    fi
    if [[ "${SIMPLECLOUDS_TEST_WEATHER_LIBRARIES:-0}" == 1 ]] && { ! grep -q '\[WEATHER-INTEGRATION\].* PASS' "$profile/logs/latest.log" || ! grep -q '\[WEATHER-WIND\].* PASS' "$profile/logs/latest.log" || ! grep -q '\[WEATHER-FOG\].* PASS' "$profile/logs/latest.log"; }; then
      echo 'Particle Rain native integration/wind/fog fixture did not complete' >&2; exit 1
    fi
    if [[ "${SIMPLECLOUDS_TEST_WEATHER_LIBRARIES:-0}" == 1 ]] && ! grep -q '\[WEATHER-MIXED-BUDGET\] PASS actual native dust+CustomParticle cap2' "$profile/logs/latest.log"; then
      echo 'Shared native dust/rain positive-budget fixture did not complete' >&2; exit 1
    fi
    if [[ "${SIMPLECLOUDS_TEST_NATIVE_BIOME_WEATHER:-0}" == 1 ]] && ! grep -q '\[NATIVE-BIOME\] PASS native sound wet/global-clear gate, actual position adapter, dry/global-storm rejection and root disable' "$profile/logs/latest.log"; then
      echo 'Native biome weather fixture did not complete local/global and root-disable assertions' >&2; exit 1
    fi
    if [[ "${SIMPLECLOUDS_TEST_NATIVE_BIOME_WEATHER:-0}" == 1 ]] && ! grep -q '\[NATIVE-MATRIX\] PASS actual registered callbacks/desert dust/snowy blizzard sound/plain exclusion with global clear' "$profile/logs/latest.log"; then
      echo 'Registered native biome dust/sound matrix did not complete' >&2; exit 1
    fi
    if [[ "${SIMPLECLOUDS_TEST_NATIVE_BIOME_WEATHER:-0}" == 1 ]] && ! grep -q '\[NATIVE-AMBIENT\] PASS actual upstream memoized palette/provider, dry survival, storm dry removal and biome root cleanup' "$profile/logs/latest.log"; then
      echo 'Native ambient/storm provenance fixture did not complete' >&2; exit 1
    fi
    if [[ "${SIMPLECLOUDS_TEST_NATIVE_BIOME_WEATHER:-0}" == 1 ]] && ! grep -q '\[NATIVE-DUST\] PASS actual provider/shared nonzero wind/native speed/root disable/dry boundary/ORIGINAL independence/zero joint budget/unrelated vanilla preservation' "$profile/logs/latest.log"; then
      echo 'Native dust fixture did not complete wind, lifetime and shared budget assertions' >&2; exit 1
    fi
    if [[ "${SIMPLECLOUDS_TEST_DH_FOG_LIFECYCLE:-0}" == 1 ]] && ! grep -q '\[DH-FOG-LIFECYCLE\] PASS root disable/preexisting overrides/newer unrelated override ownership' "$profile/logs/latest.log"; then
      echo 'DH fog lifecycle fixture did not complete all ownership assertions' >&2; exit 1
    fi
    if [[ "${SIMPLECLOUDS_TEST_DH_FOG_LIFECYCLE:-0}" == 1 ]] && { ! grep -q '\[DH-FOG-LIFECYCLE\] PASS actual dry-camera eligibility' "$profile/logs/latest.log" || ! grep -q '\[DH-FOG-LIFECYCLE\] PASS actual resource reload and active override cleanup' "$profile/logs/latest.log"; }; then
      echo 'DH fog fixture did not complete dry-camera and actual resource-reload assertions' >&2; exit 1
    fi
    if [[ "${SIMPLECLOUDS_TEST_COUNTER_RELOAD:-0}" == 1 ]] && ! grep -q '\[COUNTER-RELOAD\] PASS two actual resource reloads' "$profile/logs/latest.log"; then
      echo 'Actual snapshot resource reload fixture not verified' >&2; exit 1
    fi
    if [[ "$test_uncapped" == 1 ]] && ! grep -q '\[DEVSHOT\] MATCHSHAKE.*fpsCap=260' "$profile/logs/latest.log"; then
      echo 'Actual uncapped fixture not verified' >&2; exit 1
    fi
    if [[ -n "$shader_pack" ]] && { ! grep -q '\[PIPELINE-SELECTION\] shadersRunning=true' "$profile/logs/latest.log" || ! grep -q '\[SHADER-CLOUD-STAGE\] completed=true atmosphere=false' "$profile/logs/latest.log"; }; then
      echo 'Actual Iris selection/late GPU stage not verified' >&2; exit 1
    fi
    if [[ "$roof_test" == 1 ]] && { ! grep -q '\[ROOF-PROBE\] open verified' "$profile/logs/latest.log" || ! grep -q '\[ROOF-PROBE\] shelter collision verified' "$profile/logs/latest.log" || ! grep -q '\[ROOF-PROBE\] exit verified' "$profile/logs/latest.log"; }; then
      echo 'Fullpack roof lifecycle not verified' >&2; exit 1
    fi
    if [[ "$test_native_roof" == 1 ]]; then
      for phase in open covered reopened; do
        grep -q "\[NATIVE-RAIN-ROOF\] $phase PASS actual sky spawner" "$profile/logs/latest.log" || { echo "Native rain roof $phase not verified" >&2; exit 1; }
      done
    fi
    if grep -Eq "$runtime_errors" "$profile/logs/latest.log"; then
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

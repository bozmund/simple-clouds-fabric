#!/usr/bin/env bash
set -euo pipefail
cd /home/jan/simple-clouds-fabric
unit=simpleclouds-devclient
variant=${1:-compat}
case "$variant" in compat|pure|pureconfigcommands|purestorm|purestormvanilla|purestormsnow|purecustomsnow|pureparticlesnow|puresnowroof|pureweathercycle|purepause|purewater|purewaterrain|pureroof|pureroofglass|pure-sky|purelong|purelong-noresolve|preview) ;; *) echo 'Unknown isolated variant' >&2; exit 2;; esac
preview_test=0
capture_pattern='devshot-SHAKE-*.png'
expected_frames=41
skip_oit_resolve=0
skip_opaque=0
if [[ "$variant" == purelong || "$variant" == purelong-noresolve ]]; then expected_frames=121; fi
if [[ "$variant" == purestorm || "$variant" == purestormvanilla || "$variant" == purestormsnow ]]; then expected_frames=81; fi
vanilla_weather_test=0
snow_weather_test=0
particle_snow_test=0
if [[ "$variant" == pureparticlesnow ]]; then
  [[ "${ORG_GRADLE_PROJECT_integratedWeather:-false}" == true ]] || { echo 'Particle snow requires integrated weather libraries' >&2; exit 2; }
  particle_snow_test=1
fi
weather_cycle_test=0
pause_test=0
water_test=0
roof_test=0
config_test=0
world_config='run/saves/CodexConfigCommands20261001/serverconfig/simpleclouds-server.toml'
if [[ "$variant" == pureconfigcommands ]]; then config_test=1; fi
if [[ "$variant" == pureroof || "$variant" == pureroofglass ]]; then expected_frames=81; roof_test=1; fi
if [[ "$variant" == puresnowroof ]]; then expected_frames=81; roof_test=1; snow_weather_test=1; fi
if [[ "$variant" == purewater ]]; then expected_frames=81; vanilla_weather_test=1; water_test=1; fi
if [[ "$variant" == purewaterrain ]]; then expected_frames=81; water_test=1; fi
if [[ "$variant" == purepause ]]; then expected_frames=81; vanilla_weather_test=1; pause_test=1; fi
if [[ "$variant" == pureweathercycle ]]; then expected_frames=81; vanilla_weather_test=1; weather_cycle_test=1; fi
if [[ "$variant" == purestormvanilla || "$variant" == purestormsnow ]]; then vanilla_weather_test=1; fi
if [[ "$variant" == purestormsnow || "$variant" == purecustomsnow || "$variant" == pureparticlesnow ]]; then snow_weather_test=1; expected_frames=81; fi
if [[ "$variant" == purelong-noresolve ]]; then skip_oit_resolve=1; fi
if [[ "$variant" == pure-sky ]]; then skip_oit_resolve=1; skip_opaque=1; fi
if [[ "$variant" == preview ]]; then preview_test=1; capture_pattern='devshot-PREVIEW-*.png'; expected_frames=12; fi
dh_jar=/home/jan/simple-clouds-fabric/run/mods/DistantHorizons-3.3.2-dev-26.3-d0efcc17e.jar
for other in simpleclouds-devclient simpleclouds-realgame simpleclouds-refclient; do
  if systemctl --user is-active --quiet "$other"; then echo "Concurrent owned client: $other" >&2; exit 1; fi
done
for pid in $(pgrep -x 'java|\.java-wrapped' 2>/dev/null || true); do
  if tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | grep -qE 'KnotClient|cpw\.mods\.bootstraplauncher\.BootstrapLauncher|net\.minecraftforge\.userdev\.LaunchTesting'; then
    echo "Concurrent Minecraft PID $pid" >&2; exit 1
  fi
done
bash tools/test-memory-budget.sh check
evidence=$(mktemp -d "$PWD/evidence-codex-20260930-isolated-motion-${variant}-XXXXXX")
mkdir -p "$evidence/before/screenshots" "$evidence/test/screenshots" run/screenshots
if [[ "$config_test" == 1 ]]; then
  mkdir -p "$evidence/before/config" "$evidence/test/config"
  for file in simpleclouds-server.toml simpleclouds-common.toml; do
    [[ ! -L "run/config/$file" ]] || { echo 'Unsafe config test input' >&2; exit 1; }
    [[ -f "run/config/$file" ]] && cp -p "run/config/$file" "$evidence/before/config/"
  done
  [[ ! -L "$world_config" ]] || { echo 'Unsafe world config test input' >&2; exit 1; }
  [[ -f "$world_config" ]] && cp -p "$world_config" "$evidence/before/world-server.toml"
fi
for file in run/screenshots/devshot*.png; do
  [[ -f "$file" ]] && cp -p "$file" "$evidence/before/screenshots/"
done
for file in run/devshot.request run/logs/latest.log run/options.txt; do
  [[ -f "$file" ]] && cp -p "$file" "$evidence/before/"
done
started=0
dh_moved=0
cleanup() {
  result=$?; trap - EXIT
  if [[ "$started" == 1 ]]; then
    journalctl --user -u "$unit" --since "@$started_at" --no-pager > "$evidence/service-journal.log" || true
    systemctl --user show "$unit" -p MemoryCurrent -p MemoryPeak -p ActiveState > "$evidence/unit-memory.log" || true
    systemctl --user stop "$unit" || true
    [[ -f run/logs/latest.log ]] && cp -p run/logs/latest.log "$evidence/test/latest.log"
    for file in run/screenshots/devshot*.png; do
      [[ -f "$file" ]] && cp -p "$file" "$evidence/test/screenshots/"
    done
  fi
  for file in run/screenshots/devshot*.png; do [[ -f "$file" ]] && rm -- "$file"; done
  for file in "$evidence/before/screenshots/"*.png; do [[ -f "$file" ]] && cp -p "$file" run/screenshots/; done
  if [[ -f "$evidence/before/devshot.request" ]]; then cp -p "$evidence/before/devshot.request" run/devshot.request; else rm -f -- run/devshot.request; fi
  [[ -f "$evidence/before/latest.log" ]] && cp -p "$evidence/before/latest.log" run/logs/latest.log
  [[ -f "$evidence/before/options.txt" ]] && cp -p "$evidence/before/options.txt" run/options.txt
  if [[ "$config_test" == 1 ]]; then
    for file in simpleclouds-server.toml simpleclouds-common.toml; do
      [[ -f "run/config/$file" ]] && cp -p "run/config/$file" "$evidence/test/config/"
      if [[ -f "$evidence/before/config/$file" ]]; then
        cp -p "$evidence/before/config/$file" "run/config/$file"
        cmp -s "$evidence/before/config/$file" "run/config/$file" || result=1
      else rm -f -- "run/config/$file"; fi
    done
    [[ -f "$world_config" ]] && cp -p "$world_config" "$evidence/test/world-server.toml"
    if [[ -f "$evidence/before/world-server.toml" ]]; then
      cp -p "$evidence/before/world-server.toml" "$world_config"
      cmp -s "$evidence/before/world-server.toml" "$world_config" || result=1
    else rm -f -- "$world_config"; fi
  fi
  if [[ "$dh_moved" == 1 ]]; then
    if [[ -e "$dh_jar" ]]; then
      echo "Refusing to overwrite a newly created DH jar; preserved original at $evidence/before/dh.jar" >&2
      result=1
    else
      mv -- "$evidence/before/dh.jar" "$dh_jar"
      sha256sum "$dh_jar" > "$evidence/restored-dh.sha256"
    fi
  fi
  printf 'Result=%s Evidence=%s\n' "$result" "$evidence"
  exit "$result"
}
trap cleanup EXIT
if [[ "$variant" != compat && -f "$dh_jar" ]]; then
  sha256sum "$dh_jar" > "$evidence/before/dh.sha256"
  mv -- "$dh_jar" "$evidence/before/dh.jar"
  dh_moved=1
fi
for file in run/screenshots/devshot*.png; do [[ -f "$file" ]] && rm -- "$file"; done
printf '240 CREATEFLAT CodexMatchedMotion20260930 20260929 MATCHSHAKE\n' > run/devshot.request
if [[ "$variant" == pureconfigcommands ]]; then printf '240 CREATEFLAT CodexConfigCommands20261001 20260929 MATCHSHAKE\n' > run/devshot.request; fi
if [[ "$variant" == purestorm || "$variant" == purestormvanilla ]]; then printf '240 CREATEFLAT CodexTerrainFog20261001 20260929 GRIDSTORM\n' > run/devshot.request; fi
if [[ "$variant" == purestormsnow ]]; then printf '240 CREATEFLAT CodexNativeSnow20261001 20260929 GRIDSTORM\n' > run/devshot.request; fi
if [[ "$variant" == purecustomsnow ]]; then printf '240 CREATEFLAT CodexNativeSnow20261001 20260929 GRIDSTORM\n' > run/devshot.request; fi
if [[ "$variant" == pureparticlesnow ]]; then printf '240 CREATEFLAT CodexNativeSnow20261001 20260929 GRIDSTORM\n' > run/devshot.request; fi
if [[ "$variant" == puresnowroof ]]; then printf '240 CREATEFLAT CodexSnowRoof20261001 20260929 GRIDSTORM\n' > run/devshot.request; fi
if [[ "$variant" == pureweathercycle ]]; then printf '240 CREATEFLAT CodexWeatherCycle20261001 20260929 GRIDSTORM\n' > run/devshot.request; fi
if [[ "$variant" == purepause ]]; then printf '240 CREATEFLAT CodexPause20261001 20260929 GRIDSTORM\n' > run/devshot.request; fi
if [[ "$variant" == purewater ]]; then printf '240 CREATEFLAT CodexWater20261001 20260929 GRIDSTORM\n' > run/devshot.request; fi
if [[ "$variant" == purewaterrain ]]; then printf '240 CREATEFLAT CodexWater20261001 20260929 GRIDSTORM\n' > run/devshot.request; fi
if [[ "$variant" == pureroof ]]; then printf '240 CREATEFLAT CodexRoof20261001 20260929 GRIDSTORM\n' > run/devshot.request; fi
if [[ "$variant" == pureroofglass ]]; then printf '240 CREATEFLAT CodexGlassRoof20261001 20260929 GRIDSTORM\n' > run/devshot.request; fi
if [[ "$variant" == purelong || "$variant" == purelong-noresolve ]]; then printf '240 CREATEFLAT CodexMatchedMotionLong20260930 20260929 LONGSHAKE\n' > run/devshot.request; fi
if [[ "$variant" == preview ]]; then printf '240 CREATEFLAT CodexPreview20260930 20260930\n' > run/devshot.request; fi
started_at=$(date +%s)
systemd-run --user --unit="$unit" --collect --quiet --expand-environment=no \
  --property="WorkingDirectory=$PWD" --property=MemoryHigh=7G --property=MemoryMax=9G \
  --property=TimeoutStopSec=8s --property=KillMode=control-group \
  --property=Environment=SIMPLECLOUDS_DEV=1 \
  --property="Environment=SIMPLECLOUDS_TEST_IMAGE_API=${SIMPLECLOUDS_TEST_IMAGE_API:-0}" \
  --property="Environment=SIMPLECLOUDS_TEST_WEATHER_LIBRARIES=${SIMPLECLOUDS_TEST_WEATHER_LIBRARIES:-0}" \
  --property="Environment=ORG_GRADLE_PROJECT_integratedWeather=${ORG_GRADLE_PROJECT_integratedWeather:-false}" \
  --property="Environment=SIMPLECLOUDS_TEST_VANILLA_WEATHER=$vanilla_weather_test" \
  --property="Environment=SIMPLECLOUDS_TEST_SNOW=$snow_weather_test" \
  --property="Environment=SIMPLECLOUDS_TEST_PARTICLE_SNOW=$particle_snow_test" \
  --property="Environment=SIMPLECLOUDS_TEST_WEATHER_CYCLE=$weather_cycle_test" \
  --property="Environment=SIMPLECLOUDS_TEST_PAUSE=$pause_test" \
  --property="Environment=SIMPLECLOUDS_TEST_WATER=$water_test" \
  --property="Environment=SIMPLECLOUDS_TEST_ROOF=$roof_test" \
  --property="Environment=SIMPLECLOUDS_TEST_CONFIG_COMMANDS=$config_test" \
  --property="Environment=SIMPLECLOUDS_TEST_REPEAT_FOG=${SIMPLECLOUDS_TEST_REPEAT_FOG:-0}" \
  --property="Environment=SIMPLECLOUDS_TEST_REPEAT_SKY=${SIMPLECLOUDS_TEST_REPEAT_SKY:-0}" \
  --property="Environment=SIMPLECLOUDS_TEST_PIPELINE_API=${SIMPLECLOUDS_TEST_PIPELINE_API:-0}" \
  --property="Environment=SIMPLECLOUDS_TEST_SHADER_STAGE=${SIMPLECLOUDS_TEST_SHADER_STAGE:-0}" \
  --property="Environment=SIMPLECLOUDS_PREVIEW_TEST=$preview_test" \
  --property="Environment=SIMPLECLOUDS_DIAGNOSTIC_SKIP_OIT_RESOLVE=$skip_oit_resolve" \
  --property="Environment=SIMPLECLOUDS_DIAGNOSTIC_SKIP_OPAQUE=$skip_opaque" \
  --property="Environment=SIMPLECLOUDS_PROFILE=${SIMPLECLOUDS_PROFILE:-0}" \
  --property="Environment=SIMPLECLOUDS_TEST_OIT_MRT=${SIMPLECLOUDS_TEST_OIT_MRT:-auto}" \
  bash -c '
    live_display=${DISPLAY-}; live_auth=${XAUTHORITY-}
    live_wayland=${WAYLAND_DISPLAY-}; live_runtime=${XDG_RUNTIME_DIR-}
    while IFS= read -r -d "" kv; do export "$kv"; done < /home/jan/.cache/simpleclouds/launch.env
    export DISPLAY="$live_display" XAUTHORITY="$live_auth" WAYLAND_DISPLAY="$live_wayland" XDG_RUNTIME_DIR="$live_runtime"
    bash tools/test-memory-budget.sh watch simpleclouds-devclient & monitor=$!
    trap "kill $monitor 2>/dev/null || true" EXIT
    ./gradlew --offline --no-daemon --max-workers=4 runClient --console=plain
  ' > "$evidence/launcher.log" 2>&1
started=1
for attempt in $(seq 1 120); do
  if [[ -f run/logs/latest.log && "$(stat -c %Y run/logs/latest.log)" -ge "$started_at" ]]; then
    if grep -Eq 'Simple Clouds ERROR|cloud render pass failed|after-sky cloud pass failed|atmospheric sky pass failed|preview render pass failed|Failed to set up the 26.2 cloud pipeline|Failed to map buffer|Resource reload failed' run/logs/latest.log; then
      echo 'Isolated runtime error gate failed' >&2; exit 1
    fi
  fi
  count=$(find run/screenshots -maxdepth 1 -name "$capture_pattern" -type f | wc -l)
  printf 'frames=%s\n' "$count" >> "$evidence/progress.log"
  if (( count == expected_frames )); then
    if [[ "${SIMPLECLOUDS_TEST_IMAGE_API:-0}" == 1 ]] && ! grep -q '\[IMAGE-API\].* PASS' run/logs/latest.log; then
      echo 'Actual legacy image API render/export/lifetime not verified' >&2; exit 1
    fi
    if [[ "${SIMPLECLOUDS_TEST_WEATHER_LIBRARIES:-0}" == 1 ]] && ! grep -q '\[WEATHER-INTEGRATION\].* PASS' run/logs/latest.log; then
      echo 'Actual integrated weather ownership/lifetime not verified' >&2; exit 1
    fi
    if [[ "$preview_test" == 1 ]] && { ! grep -q '\[PREVIEW-PROBE\] GUI weather/export/import/resize/overwrite rejection/cancel/invalid load PASS' run/logs/latest.log || ! grep -q '\[PREVIEW-PROBE\] PNG export PASS' run/logs/latest.log || ! grep -q '\[PREVIEW-PROBE\] menu-only preview/rotate/zoom/resize/PNG PASS' run/logs/latest.log; }; then
      echo 'Actual editor file/weather GUI workflow not verified' >&2; exit 1
    fi
    if [[ "$config_test" == 1 ]] && ! grep -q '\[SERVER-CONFIG-COMMANDS\] saved world exit verified: server spec unloaded' run/logs/latest.log; then
      sleep 2
      continue
    fi
    if [[ "$config_test" == 1 ]] && { ! grep -q '\[SERVER-CONFIG-COMMANDS\] actual dispatcher verified' run/logs/latest.log || ! grep -q '\[SERVER-CONFIG-COMMANDS\] original values restored' run/logs/latest.log; }; then
      echo 'Actual server config dispatcher/restoration not verified' >&2; exit 1
    fi
    if [[ "$config_test" == 1 ]] && { ! grep -q '\[SERVER-CONFIG-SYNC\] initial four-field snapshot verified' run/logs/latest.log || ! grep -q '\[SERVER-CONFIG-SYNC\] changed blacklist snapshot verified over connection' run/logs/latest.log || ! grep -q '\[SERVER-CONFIG-SYNC\] restored snapshot verified over connection' run/logs/latest.log; }; then
      echo 'Actual server config packet synchronization/restoration not verified' >&2; exit 1
    fi
    if [[ "$config_test" == 1 ]] && { ! grep -q '\[SERVER-CONFIG-RELOAD\] external file edit reloaded and synchronized' run/logs/latest.log || ! grep -q '\[SERVER-CONFIG-RELOAD\] external file restoration reloaded and synchronized' run/logs/latest.log; }; then
      echo 'Actual server config external file reload/restoration not verified' >&2; exit 1
    fi
    if [[ "$variant" == pureroof || "$variant" == pureroofglass || "$variant" == puresnowroof ]] && { ! grep -q '\[ROOF-PROBE\] open verified' run/logs/latest.log || ! grep -q '\[ROOF-PROBE\] shelter collision verified' run/logs/latest.log || ! grep -q '\[ROOF-PROBE\] exit verified' run/logs/latest.log; }; then
      echo 'Actual precipitation shelter lifecycle not verified' >&2; exit 1
    fi
    if [[ "${SIMPLECLOUDS_TEST_PIPELINE_API:-0}" == 1 && "${SIMPLECLOUDS_TEST_SHADER_STAGE:-0}" == 0 ]] && ! grep -q '\[PIPELINE-API\] event override verified' run/logs/latest.log; then
      echo 'Live pipeline event override lifecycle not verified' >&2; exit 1
    fi
    if [[ "${SIMPLECLOUDS_TEST_SHADER_STAGE:-0}" == 1 ]] && ! grep -q '\[PIPELINE-API\] late stage verified earlyClouds=0 lateClouds=1 defaultFog=0' run/logs/latest.log; then
      echo 'Shader late-stage ownership not verified' >&2; exit 1
    fi
    if [[ "${SIMPLECLOUDS_TEST_REPEAT_SKY:-0}" == 1 ]] && ! grep -q '\[SKY-API-REPLAY\] two API calls, completedCloudStages=1' run/logs/latest.log; then
      echo 'Repeated sky API call did not verify one completed cloud stage' >&2; exit 1
    fi
    if [[ "$variant" == purewater || "$variant" == purewaterrain ]] && { ! grep -q '\[WATER-PROBE\] above verified' run/logs/latest.log || ! grep -q '\[WATER-PROBE\] submerged verified' run/logs/latest.log || ! grep -q '\[WATER-PROBE\] exit verified' run/logs/latest.log; }; then
      echo 'Actual water entry/exit lifecycle not verified' >&2; exit 1
    fi
    if [[ "$variant" == purewaterrain ]] && ! grep -q '\[CUSTOM-WEATHER-PASS\] encoded=true' run/logs/latest.log; then
      echo 'Actual custom precipitation pass not encoded' >&2; exit 1
    fi
    if [[ "$variant" == purepause ]] && { ! grep -q '\[PAUSE-PROBE\] frozen verified' run/logs/latest.log || ! grep -q '\[PAUSE-PROBE\] resume verified' run/logs/latest.log; }; then
      echo 'Actual pause/resume lifecycle not verified' >&2; exit 1
    fi
    if [[ "$variant" == purepause ]] && ! cmp -s run/screenshots/devshot-PAUSE-START.png run/screenshots/devshot-PAUSE.png; then
      echo 'Paused world screenshots differ or are missing' >&2; exit 1
    fi
    if [[ "$variant" == pureweathercycle ]] && { ! grep -q '\[WEATHER-CYCLE\] clear verified rain=0.0 replays=0' run/logs/latest.log || ! grep -q '\[WEATHER-CYCLE\] wet verified' run/logs/latest.log; }; then
      echo 'Weather clear/re-entry lifecycle not verified' >&2; exit 1
    fi
    if [[ "${SIMPLECLOUDS_TEST_REPEAT_FOG:-0}" == 1 ]] && ! grep -q '\[WORLD-FOG-REPLAY\] two API calls, actualGpuPasses=1' run/logs/latest.log; then
      echo 'Repeated API call did not verify exactly one GPU fog pass' >&2; exit 1
    fi
    if [[ "$variant" == purestormsnow ]] && ! grep -Eq '\[DEFERRED-WEATHER\].*snow=[1-9]' run/logs/latest.log; then
      echo 'Snow replay not observed; screenshots alone are insufficient' >&2; exit 1
    fi
    if [[ "$variant" == purecustomsnow || "$variant" == puresnowroof ]] && ! grep -Eq '\[CUSTOM-WEATHER-PASS\] encoded=true.*texture=.*snow.*quads=[1-9]' run/logs/latest.log; then
      echo 'Custom snow batch not encoded; native replay cannot substitute for it' >&2; exit 1
    fi
    if [[ "$variant" == puresnowroof ]]; then
      for phase in 'open verified' 'shelter collision verified' 'exit verified'; do
        if ! grep -Eq "\[ROOF-PROBE\] $phase.*rain=0 snow=[1-9]" run/logs/latest.log; then
          echo "Original camera-biome snow ownership not verified in phase: $phase" >&2; exit 1
        fi
      done
    fi
    if [[ "$variant" == pureparticlesnow ]] && ! grep -Eq '\[PARTICLE-SNOW-RENDER\] PASS actual naturally spawned SNOW data/snow textures/extractedQuads=[1-9]' run/logs/latest.log; then
      echo 'Actual naturally spawned Particle Rain snow render submission not verified' >&2; exit 1
    fi
    if [[ "$variant" == purestormsnow || "$variant" == purecustomsnow || "$variant" == pureparticlesnow || "$variant" == puresnowroof ]] && { ! grep -Eq 'native snow fixture center=.*biome=.*minecraft:snowy_plains' run/logs/latest.log || grep -q 'native snow fixture preparation failed' run/logs/latest.log; }; then
      echo 'Snow fixture preparation not verified' >&2; exit 1
    fi
    if [[ "$variant" == purestormvanilla && "${SIMPLECLOUDS_TEST_SHADER_STAGE:-0}" == 0 ]] && ! grep -Eq '\[DEFERRED-WEATHER\].*rain=[1-9]' run/logs/latest.log; then
      echo 'Rain replay not observed; screenshots alone are insufficient' >&2; exit 1
    fi
    if [[ "${SIMPLECLOUDS_TEST_SHADER_STAGE:-0}" == 1 ]] && { ! grep -Eq '\[NATIVE-WEATHER-RETURN\] rain=[1-9].*deferred=false' run/logs/latest.log || grep -q '\[DEFERRED-WEATHER\]' run/logs/latest.log; }; then
      echo 'Late-stage native precipitation ownership not verified' >&2; exit 1
    fi
    echo "Isolated captured $expected_frames frames; visual review required"; exit 0
  fi
  if ! systemctl --user is-active --quiet "$unit"; then
    journalctl --user -u "$unit" -n 35 --no-pager > "$evidence/unit-failure.log"
    echo 'Isolated client terminated before capture' >&2; exit 1
  fi
  sleep 2
done
echo 'Isolated capture timed out' >&2; exit 1

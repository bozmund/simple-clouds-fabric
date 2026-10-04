#!/usr/bin/env bash
# One-shot dev test loop for the Simple Clouds port.
#   ./dev-relaunch.sh            stop the previous DEV client, build + launch ONE dev
#                                client, wait for the first cloud draw, check the log,
#                                take a deterministic in-game screenshot
#   ./dev-relaunch.sh --install  same, and on PASS also build the jar and copy it into
#                                the real profile's mods/ folder
# Ends with PASS, FAIL (build) or FAIL (runtime). PASS only means the log is clean --
# you must still READ the screenshot: "first draw, N instances" never proved the
# clouds were visible.
set -u
cd "$(dirname "$0")"
PROJECT="$(pwd)"
ENVF="$HOME/.cache/simpleclouds/launch.env"   # env of a known-good launch (display, Vulkan, libs)
OUT="$HOME/.cache/simpleclouds/run.out"
LOG=run/logs/latest.log
REQUEST=run/devshot.request   # read by the mod (client/DevShot.java)
DEVSHOT=run/screenshots/devshot.png
SHOT=/tmp/sc-latest.png
# DEVSHOT_EXTRA="A B C D E [NOSPAWN]" makes the mod take the standard views
# (VISUAL-PARITY-PLAN step 0) as devshot-A.png ... devshot-E2.png; without view
# tokens it takes the legacy single straight-up shot to devshot.png.
UNIT=simpleclouds-devclient
FRAMES=240                    # rendered frames after joining the world before the shot
VERSION=$(sed -n 's/^mod_version=//p' gradle.properties | tr -d '\r')
ARCHIVE=$(sed -n 's/^archives_base_name=//p' gradle.properties | tr -d '\r')
JAR="build/libs/$ARCHIVE-$VERSION.jar"
MODS="$HOME/.local/share/ModrinthApp/profiles/Fabric 26.3/mods"

load_env() { while IFS= read -r -d "" kv; do export "$kv"; done < "$ENVF"; }

# 1. Stop the previous DEV client only. Never match "KnotClient": the real
#    Modrinth game is a KnotClient too, and killing it would end Jan's session.
systemctl --user stop "$UNIT" 2>/dev/null
systemctl --user reset-failed "$UNIT" 2>/dev/null
sleep 3
# Do not compete with an independently launched player game, including Nix's
# wrapped Java executable name. This is discovery only, never a kill pattern.
for p in $(pgrep -x 'java|\.java-wrapped' 2>/dev/null); do
  if tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null | grep -qE 'KnotClient|cpw\.mods\.bootstraplauncher\.BootstrapLauncher|net\.minecraftforge\.userdev\.LaunchTesting'; then
    echo "FAIL (preflight): another Minecraft client (pid $p) is running; no new test started."
    exit 1
  fi
done
bash tools/test-memory-budget.sh check || exit 1

# Automated evidence runs must not leave a heavy game running after completion
# or failure. Explicit DEV_KEEP_RUNNING=1 retains a successful play-test client.
cleanup_test() {
  status=$?
  if [ "$status" -ne 0 ] || [ "${DEV_KEEP_RUNNING:-0}" != 1 ]; then
    systemctl --user stop "$UNIT" 2>/dev/null || true
  fi
  return "$status"
}
trap cleanup_test EXIT

# 2. Launch as its own user unit: it survives the symbiote_job that ran this
#    script (so Jan can play-test), and the fixed unit name makes a second
#    concurrent dev client impossible.
# A1 (VISUAL-PARITY-PLAN addendum):
#  - --no-daemon: the Gradle client JVM must be a CHILD of this systemd unit, not
#    of a daemon started inside the terminal (a 2026-09-13 daemon-forked client
#    lived in the tmux scope and its 25 GB OOM-kill took the whole terminal down).
#  - MemoryHigh/MemoryMax: cgroup ceiling for the unit; a leak kills only the
#    client, never the host or the terminal.
#  - SIMPLECLOUDS_DEV=1: enables the in-game 30 s heap/direct/RSS logger
#    (client/DevMemoryLogger.java); the real profile never sees it.
mkdir -p run/screenshots
rm -f run/screenshots/devshot*.png
TOKENS="${DEVSHOT_ANGLE:-} ${DEVSHOT_YAW:-} ${DEVSHOT_EXTRA:-}"; [ -n "${DEVSHOT_NOSHADOW:-}" ] && TOKENS="$TOKENS NOSHADOW"
echo "$FRAMES $TOKENS" | sed 's/ *$//g; s/  */ /g' > "$REQUEST"   # optional tokens: camera xRot (default -90), yaw, NOSHADOW, LOOP
T0=$(date +%s)
systemd-run --user --unit="$UNIT" --collect --quiet --expand-environment=no \
  --property=WorkingDirectory="$PROJECT" \
  --property=MemoryHigh=9G --property=MemoryMax=10G \
  --property=TimeoutStopSec=8s --property=KillMode=control-group \
  --property=Environment=SIMPLECLOUDS_DEV=1 \
  --property=Environment=SIMPLECLOUDS_CPU_BUDGET_PROBE=${SIMPLECLOUDS_CPU_BUDGET_PROBE:-0} \
  --property=Environment=SIMPLECLOUDS_GPU_WORLD=${SIMPLECLOUDS_GPU_WORLD:-0} \
  --property=Environment=SIMPLECLOUDS_GPU_DIRECT_OPAQUE=${SIMPLECLOUDS_GPU_DIRECT_OPAQUE:-0} \
  --property=Environment=SIMPLECLOUDS_GPU_DIRECT_TRANSPARENT=${SIMPLECLOUDS_GPU_DIRECT_TRANSPARENT:-0} \
  --property=Environment=SIMPLECLOUDS_GPU_STORM_BITS=${SIMPLECLOUDS_GPU_STORM_BITS:-0} \
  --property=Environment=SIMPLECLOUDS_TEST_OIT_MRT=${SIMPLECLOUDS_TEST_OIT_MRT:-auto} \
  --property=Environment=SIMPLECLOUDS_TEST_CLOUD_NO_DEPTH=${SIMPLECLOUDS_TEST_CLOUD_NO_DEPTH:-0} \
  --property=Environment=SIMPLECLOUDS_TEST_LATE_CLOUDS=${SIMPLECLOUDS_TEST_LATE_CLOUDS:-0} \
  --property=Environment=SIMPLECLOUDS_TEST_NO_DH_MERGE=${SIMPLECLOUDS_TEST_NO_DH_MERGE:-0} \
  --property=Environment=SIMPLECLOUDS_TEST_DH_MERGE_DEBUG=${SIMPLECLOUDS_TEST_DH_MERGE_DEBUG:-0} \
  --property=Environment=SIMPLECLOUDS_GPU_NO_READBACK=${SIMPLECLOUDS_GPU_NO_READBACK:-0} \
  --property=Environment=SIMPLECLOUDS_GPU_MAPPED_COUNTERS=${SIMPLECLOUDS_GPU_MAPPED_COUNTERS:-0} \
  --property=Environment=SIMPLECLOUDS_GPU_WORLD_SLOTS=${SIMPLECLOUDS_GPU_WORLD_SLOTS:-4} \
  --property=Environment=SIMPLECLOUDS_GPU_SHARED_OCCUPANCY=${SIMPLECLOUDS_GPU_SHARED_OCCUPANCY:-0} \
  --property=Environment=SIMPLECLOUDS_GPU_CULL_AWAY_FACES=${SIMPLECLOUDS_GPU_CULL_AWAY_FACES:-0} \
  --property=Environment=SIMPLECLOUDS_GPU_TEST_CPU_FALLBACK=${SIMPLECLOUDS_GPU_TEST_CPU_FALLBACK:-0} \
  --property=Environment=SIMPLECLOUDS_TRACE_VISUAL_CHURN=${SIMPLECLOUDS_TRACE_VISUAL_CHURN:-0} \
  bash -c '
    # Keep cached library/tool paths, but never reuse a previous login cookie.
    live_display=${DISPLAY-}; live_auth=${XAUTHORITY-}
    live_wayland=${WAYLAND_DISPLAY-}; live_runtime=${XDG_RUNTIME_DIR-}
    while IFS= read -r -d "" kv; do export "$kv"; done < "$1"
    export DISPLAY="$live_display" XAUTHORITY="$live_auth"
    export WAYLAND_DISPLAY="$live_wayland" XDG_RUNTIME_DIR="$live_runtime"
    # This watcher lives inside the same owned unit and dies with it. It never
    # unloads models or touches unrelated user processes.
    bash tools/test-memory-budget.sh watch "$4" &
    # CREATEFLAT opens its own world from DevShot. Quick Play opening the same
    # CloudClean save concurrently caused OverlappingFileLockException and the
    # misleading Minecraft "resource reload failed" toast on every test.
    if [[ " ${3^^} " == *" CREATEFLAT "* ]]; then
      exec ./gradlew --no-daemon runClient --console=plain > "$2" 2>&1
    fi
    exec ./gradlew --no-daemon runClient --console=plain --args="--quickPlaySingleplayer CloudClean" > "$2" 2>&1
  ' _ "$ENVF" "$OUT" "$TOKENS" "$UNIT" \
  || { echo "FAIL (launch): could not start user unit $UNIT"; exit 1; }
echo "launched at $(date +%H:%M:%S) as user unit $UNIT; waiting for the first cloud draw..."

# 3. Wait for the first draw, telling a compile failure apart from a runtime one.
stage=build; drawn=0
for i in $(seq 1 100); do
  sleep 4
  if [ "$stage" = build ] && grep -qE "BUILD FAILED|\.java:[0-9]+: error:" "$OUT" 2>/dev/null; then
    echo "FAIL (build): the mod does not compile"
    grep -E "\.java:[0-9]+: error:|What went wrong|BUILD FAILED" -A2 "$OUT" | head -30; exit 1
  fi
  if [ -f "$LOG" ] && [ "$(stat -c %Y "$LOG")" -ge "$T0" ]; then stage=runtime; fi
  if [ "$stage" = runtime ] && grep -q "first draw" "$LOG"; then drawn=1; break; fi
  if [ "$i" -gt 8 ] && ! systemctl --user is-active --quiet "$UNIT"; then
    echo "FAIL ($stage): the client exited before drawing clouds"; tail -40 "$OUT"; exit 1
  fi
done
if [ "$drawn" -ne 1 ]; then echo "FAIL ($stage): no cloud draw within 400s"; tail -30 "$OUT"; exit 1; fi

# 4. The mod takes its own screenshots (no HUD) once FRAMES frames have
#    rendered, so window stacking cannot hide the game. Standard-view runs
#    produce devshot-A.png ... devshot-E2.png; the legacy run devshot.png.
VIEWWANT=0
for t in ${DEVSHOT_EXTRA:-}; do
  case "$t" in
    A|B|C|D|F|G|H) VIEWWANT=$((VIEWWANT+1));;
    E) VIEWWANT=$((VIEWWANT+3));;
    SHAKE) VIEWWANT=$((VIEWWANT+41));;
    GRIDCROSS|GRIDSTORM|GRIDRAPID) VIEWWANT=$((VIEWWANT+81));;
    LONGSHAKE) VIEWWANT=$((VIEWWANT+121));;
    LONGSHAKE5) VIEWWANT=$((VIEWWANT+301));;
    PITCH) VIEWWANT=$((VIEWWANT+3));;
    STORM) VIEWWANT=$((VIEWWANT+64));;
    UNDERSTORM) VIEWWANT=$((VIEWWANT+41));;
    STORMFAR) VIEWWANT=$((VIEWWANT+3));;
    BOLTFAR) VIEWWANT=$((VIEWWANT+24));;
    INCLOUD) VIEWWANT=$((VIEWWANT+8));;
  esac
done
if [ "$VIEWWANT" -gt 0 ]; then
  WANT=$VIEWWANT
  # 240 x 2s = 8 min: the LOD field fill-wait (step 2) can take ~2-3 min before the
  # first shot, then 12s settle per view.
  for i in $(seq 1 240); do
    n=$(ls run/screenshots/devshot-*.png 2>/dev/null | wc -l)
    [ "$n" -ge "$WANT" ] && break
    if ! systemctl --user is-active --quiet "$UNIT"; then
      echo "FAIL (runtime): dev client stopped before all $WANT screenshots"; exit 1
    fi
    sleep 2
  done
  if [ "$n" -lt "$WANT" ]; then
    echo "FAIL (evidence): expected $WANT screenshots, found $n"; exit 1
  fi
else
  for i in $(seq 1 45); do [ -s "$DEVSHOT" ] && break; sleep 2; done
  if [ ! -s "$DEVSHOT" ]; then echo "FAIL (evidence): no in-game screenshot"; exit 1; fi
fi

# 5. Log check. "unsupported uniform" means a shader uses a uniform block the
#    pipeline did not declare -- it is silently NOT bound. GLSL errors look like
#    "0:12(3): error: ...".
grep "first draw" "$LOG" | tail -1
BAD=$(grep -iE "unsupported uniform|render pass failed|missing sampler|compil.*(error|fail)|[0-9]+:[0-9]+\([0-9]+\): error|/ERROR\].*simpleclouds|simpleclouds.*Exception|Caught error loading resourcepacks|OverlappingFileLockException" "$LOG" \
      | grep -vE "Unknown registry key|Could not find root Simple Clouds config" | head -20)

# Collect the shots: legacy devshot.png -> /tmp/sc-latest.png, standard views
# devshot-X.png -> /tmp/sc-view-X.png.
SHOTS=()
for f in run/screenshots/devshot*.png; do
  [ -s "$f" ] || continue
  b=$(basename "$f")
  case "$b" in
    devshot.png) cp "$f" "$SHOT"; SHOTS+=("$SHOT") ;;
    devshot-*.png) cp "$f" "/tmp/sc-view-${b#devshot-}"; SHOTS+=("/tmp/sc-view-${b#devshot-}") ;;
  esac
done
if [ ${#SHOTS[@]} -gt 0 ]; then
  SRC="in-game devshot, no HUD"
else
  echo "FAIL (evidence): no completed in-game screenshot"; exit 1
fi

if [ -n "$BAD" ]; then
  echo "FAIL (runtime): render warnings in the log:"; echo "$BAD"; echo "screenshot ($SRC): ${SHOTS[*]}"; exit 1
fi

if [ "${1:-}" = "--install" ]; then
  [ -d "$MODS" ] || { echo "FAIL (install): target profile mods directory missing"; exit 1; }
  if [ -f "$MODS/$(basename "$JAR")" ]; then
    BACKUP="evidence-before-install-$(date +%Y%m%d-%H%M%S)"
    mkdir -p "$BACKUP"
    cp "$MODS/$(basename "$JAR")" "$BACKUP/profile.jar" || exit 1
  fi
  ./gradlew --no-daemon build -x test --console=plain > "$HOME/.cache/simpleclouds/build.out" 2>&1 \
    && cp "$JAR" "$MODS/" && echo "installed $JAR into the real profile mods/" \
    || { echo "FAIL (build): jar build/install failed"; tail -20 "$HOME/.cache/simpleclouds/build.out"; exit 1; }
fi

for s in "${SHOTS[@]}"; do echo "CAPTURED: $s ($SRC). Render-log gate passed; visual review still required."; done
if [ "${DEV_KEEP_RUNNING:-0}" = 1 ]; then
  echo "The dev client keeps running as user unit $UNIT for play-testing (stop it: systemctl --user stop $UNIT)."
else
  echo "Capture completed; stopping the owned test client to release host resources."
fi

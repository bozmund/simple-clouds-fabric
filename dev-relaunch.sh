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
JAR=build/libs/simple-clouds-0.7.3+26.2-fabric.jar
MODS="../../mods"

load_env() { while IFS= read -r -d "" kv; do export "$kv"; done < "$ENVF"; }

# 1. Stop the previous DEV client only. Never match "KnotClient": the real
#    Modrinth game is a KnotClient too, and killing it would end Jan's session.
systemctl --user stop "$UNIT" 2>/dev/null
systemctl --user reset-failed "$UNIT" 2>/dev/null
pkill -f "simple-clouds/[.]gradle/loom-cache/launch[.]cfg" 2>/dev/null
pkill -f "simple-clouds/gradle/wrapper/gradle-wrapper[.]jar" 2>/dev/null
sleep 3

# 2. Launch as its own user unit: it survives the symbiote_job that ran this
#    script (so Jan can play-test), and the fixed unit name makes a second
#    concurrent dev client impossible.
mkdir -p run/screenshots
rm -f run/screenshots/devshot*.png
TOKENS="${DEVSHOT_ANGLE:-} ${DEVSHOT_YAW:-} ${DEVSHOT_EXTRA:-}"; [ -n "${DEVSHOT_NOSHADOW:-}" ] && TOKENS="$TOKENS NOSHADOW"
echo "$FRAMES $TOKENS" | sed 's/ *$//g; s/  */ /g' > "$REQUEST"   # optional tokens: camera xRot (default -90), yaw, NOSHADOW
T0=$(date +%s)
systemd-run --user --unit="$UNIT" --collect --quiet --property=WorkingDirectory="$PROJECT" \
  bash -c 'while IFS= read -r -d "" kv; do export "$kv"; done < "$1"; exec ./gradlew runClient --console=plain --args="--quickPlaySingleplayer CloudClean" > "$2" 2>&1' _ "$ENVF" "$OUT" \
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
VIEWWANT=0; for t in ${DEVSHOT_EXTRA:-}; do case "$t" in A|B|C|D|E) VIEWWANT=$((VIEWWANT+1));; esac; done
EVIEW=0; case " ${DEVSHOT_EXTRA:-} " in *" E "*) EVIEW=1;; esac
if [ "$VIEWWANT" -gt 0 ]; then
  WANT=$(( VIEWWANT + EVIEW ))   # E produces two files
  # 240 x 2s = 8 min: the LOD field fill-wait (step 2) can take ~2-3 min before the
  # first shot, then 12s settle per view.
  for i in $(seq 1 240); do
    n=$(ls run/screenshots/devshot-*.png 2>/dev/null | wc -l)
    [ "$n" -ge "$WANT" ] && break
    sleep 2
  done
else
  for i in $(seq 1 45); do [ -s "$DEVSHOT" ] && break; sleep 2; done
fi

# 5. Log check. "unsupported uniform" means a shader uses a uniform block the
#    pipeline did not declare -- it is silently NOT bound. GLSL errors look like
#    "0:12(3): error: ...".
grep "first draw" "$LOG" | tail -1
BAD=$(grep -iE "unsupported uniform|render pass failed|missing sampler|compil.*(error|fail)|[0-9]+:[0-9]+\([0-9]+\): error|simpleclouds.*(ERROR|Exception)" "$LOG" \
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
  load_env; spectacle -b -n -o "$SHOT" >/dev/null 2>&1
  SHOTS=("$SHOT"); SRC="desktop capture (no devshot within the wait -- the game window may be covered)"
fi

if [ -n "$BAD" ]; then
  echo "FAIL (runtime): render warnings in the log:"; echo "$BAD"; echo "screenshot ($SRC): ${SHOTS[*]}"; exit 1
fi

if [ "${1:-}" = "--install" ]; then
  ./gradlew build -x test --console=plain > "$HOME/.cache/simpleclouds/build.out" 2>&1 \
    && cp "$JAR" "$MODS/" && echo "installed $JAR into the real profile mods/" \
    || { echo "FAIL (build): jar build/install failed"; tail -20 "$HOME/.cache/simpleclouds/build.out"; exit 1; }
fi

for s in "${SHOTS[@]}"; do echo "PASS (log clean). Screenshot: $s ($SRC). READ it and confirm what you changed is actually visible."; done
echo "The dev client keeps running as user unit $UNIT for play-testing (stop it: systemctl --user stop $UNIT)."

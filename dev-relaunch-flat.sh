#!/usr/bin/env bash
# Step 3: superflat reference scenes on the 26.2 port.
#
#   FLAT_NAME=RefFlat FLAT_SEED=20260917 FLAT_EXTRA="A C D F" ./dev-relaunch-flat.sh
#   FLAT_NAME=RefFlat FLAT_SEED=20260917 FLAT_EXTRA="STORM"      ./dev-relaunch-flat.sh
#   FLAT_NAME=RefFlat FLAT_SEED=20260917 FLAT_EXTRA="UNDERSTORM" ./dev-relaunch-flat.sh
#   FLAT_NAME=RefFlat FLAT_SEED=20260917 FLAT_EXTRA="SHAKE"      ./dev-relaunch-flat.sh
#
# Unlike dev-relaunch.sh this launches to the TITLE screen (no quickPlay) so the
# mod's DevShot can create + load a fresh superflat world (CREATEFLAT <name>
# <seed>) before the views run. The flat views A/C/D/F stand at the spawn origin
# (identical positions to the 1.20.1 reference run). Uses its own systemd unit so
# it never collides with the CloudClean dev client.
set -u
cd "$(dirname "$0")"
PROJECT="$(pwd)"
ENVF="$HOME/.cache/simpleclouds/launch.env"
OUT="$HOME/.cache/simpleclouds/run-flat.out"
LOG=run/logs/latest.log
REQUEST=run/devshot.request
UNIT=simpleclouds-flatclient
FRAMES="${FRAMES:-240}"
FLAT_NAME="${FLAT_NAME:-RefFlat}"
FLAT_SEED="${FLAT_SEED:-20260917}"
FLAT_EXTRA="${FLAT_EXTRA:-A C D F}"
EXTRA_TOKENS="${DEVSHOT_EXTRA:-}"   # e.g. NOSPAWN

systemctl --user stop "$UNIT" 2>/dev/null
systemctl --user reset-failed "$UNIT" 2>/dev/null
sleep 3

# A fresh world every run: the request carries CREATEFLAT, and createFreshLevel
# must create (not load) the level, so remove any previous save.
rm -rf "run/saves/$FLAT_NAME"
mkdir -p run/screenshots
rm -f run/screenshots/devshot*.png

# Request: FRAMES CREATEFLAT <name> <seed> <scene tokens...> [extra tokens]
REQ="$FRAMES CREATEFLAT $FLAT_NAME $FLAT_SEED $FLAT_EXTRA $EXTRA_TOKENS"
REQ=$(echo "$REQ" | sed 's/  */ /g; s/ *$//g')
echo "$REQ" > "$REQUEST"
echo "request: $REQ"

T0=$(date +%s)
systemd-run --user --unit="$UNIT" --collect --quiet --expand-environment=no \
  --property=WorkingDirectory="$PROJECT" \
  --property=MemoryHigh=9G --property=MemoryMax=10G \
  --property=Environment=SIMPLECLOUDS_DEV=1 \
  bash -c '
    live_display=${DISPLAY-}; live_auth=${XAUTHORITY-}
    live_wayland=${WAYLAND_DISPLAY-}; live_runtime=${XDG_RUNTIME_DIR-}
    while IFS= read -r -d "" kv; do export "$kv"; done < "$1"
    export DISPLAY="$live_display" XAUTHORITY="$live_auth"
    export WAYLAND_DISPLAY="$live_wayland" XDG_RUNTIME_DIR="$live_runtime"
    exec ./gradlew --no-daemon runClient --console=plain > "$2" 2>&1
  ' _ "$ENVF" "$OUT" \
  || { echo "FAIL (launch): could not start user unit $UNIT"; exit 1; }
echo "launched at $(date +%H:%M:%S) as user unit $UNIT (title screen; CREATEFLAT $FLAT_NAME $FLAT_SEED); waiting for the first cloud draw..."

# Wait for the first draw. World creation + load adds time, so allow longer than
# the CloudClean loop (100 x 4s here vs 100 x 4s there is the same; the create
# happens inside the client before the first draw).
stage=build; drawn=0
for i in $(seq 1 120); do
  sleep 4
  if [ "$stage" = build ] && grep -qE "BUILD FAILED|\.java:[0-9]+: error:" "$OUT" 2>/dev/null; then
    echo "FAIL (build): the mod does not compile"
    grep -E "\.java:[0-9]+: error:|What went wrong|BUILD FAILED" -A2 "$OUT" | head -30; exit 1
  fi
  if grep -q "CREATEFLAT failed" "$LOG" 2>/dev/null; then
    echo "FAIL (createflat): the superflat world creation threw; see log:"
    grep -A30 "CREATEFLAT failed" "$LOG" | head -40; exit 1
  fi
  if [ -f "$LOG" ] && [ "$(stat -c %Y "$LOG")" -ge "$T0" ]; then stage=runtime; fi
  if [ "$stage" = runtime ] && grep -q "first draw" "$LOG"; then drawn=1; break; fi
  if [ "$i" -gt 8 ] && ! systemctl --user is-active --quiet "$UNIT"; then
    echo "FAIL ($stage): the client exited before drawing clouds"; tail -40 "$OUT"; exit 1
  fi
done
if [ "$drawn" -ne 1 ]; then echo "FAIL ($stage): no cloud draw within 480s"; tail -30 "$OUT"; exit 1; fi

# How many screenshots to expect for the requested scene tokens.
VIEWWANT=0
for t in $FLAT_EXTRA; do
  case "$t" in
    A|B|C|D|F|G|H) VIEWWANT=$((VIEWWANT+1));;
    E) VIEWWANT=$((VIEWWANT+3));;
    SHAKE) VIEWWANT=$((VIEWWANT+41));;
    STORM) VIEWWANT=$((VIEWWANT+64));;
    UNDERSTORM) VIEWWANT=$((VIEWWANT+41));;
  esac
done
WANT=$VIEWWANT
# 240 x 2s = 8 min budget: world settle + LOD fill + per-view settle.
n=0
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

grep "first draw" "$LOG" | tail -1
BAD=$(grep -iE "unsupported uniform|render pass failed|missing sampler|compil.*(error|fail)|[0-9]+:[0-9]+\([0-9]+\): error|simpleclouds.*(ERROR|Exception)" "$LOG" \
      | grep -vE "Unknown registry key|Could not find root Simple Clouds config" | head -20)

SHOTS=()
for f in run/screenshots/devshot*.png; do
  [ -s "$f" ] || continue
  b=$(basename "$f")
  case "$b" in
    devshot.png) cp "$f" "/tmp/sc-latest.png"; SHOTS+=("/tmp/sc-latest.png") ;;
    devshot-*.png) cp "$f" "/tmp/sc-view-${b#devshot-}"; SHOTS+=("/tmp/sc-view-${b#devshot-}") ;;
  esac
done
if [ ${#SHOTS[@]} -eq 0 ]; then echo "FAIL (evidence): no completed in-game screenshot"; exit 1; fi

if [ -n "$BAD" ]; then
  echo "FAIL (runtime): render warnings in the log:"; echo "$BAD"; echo "screenshots: ${SHOTS[*]}"; exit 1
fi

for s in "${SHOTS[@]}"; do echo "CAPTURED: $s"; done
echo "The flat dev client keeps running as user unit $UNIT (stop it: systemctl --user stop $UNIT)."

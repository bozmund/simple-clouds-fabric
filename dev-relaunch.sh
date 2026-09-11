#!/usr/bin/env bash
# One-shot dev test loop for the Simple Clouds port.
#   ./dev-relaunch.sh            stop stale clients, build + launch ONE dev client,
#                                wait for the first cloud draw, check the log, screenshot
#   ./dev-relaunch.sh --install  same, and on PASS also build the jar and copy it into
#                                the real profile's mods/ folder
# Ends with PASS or FAIL. PASS only means the log is clean -- you must still READ the
# screenshot: "first draw, N instances" never proved the clouds were visible.
set -u
cd "$(dirname "$0")"
ENVF="$HOME/.cache/simpleclouds/launch.env"   # env of a known-good launch (display, Vulkan, libs)
OUT="$HOME/.cache/simpleclouds/run.out"
LOG=run/logs/latest.log
SHOT=/tmp/sc-latest.png
JAR=build/libs/simple-clouds-0.7.3+26.2-fabric.jar
MODS="../../mods"

load_env() { while IFS= read -r -d "" kv; do export "$kv"; done < "$ENVF"; }

# 1. Never run two clients: a stale one silently keeps showing an old build.
#    The [K]/[G] brackets stop pkill from matching this script's own command line.
pkill -f "[K]notClient" 2>/dev/null
pkill -f "[G]radleWrapperMain" 2>/dev/null
sleep 3

# 2. Launch (runClient compiles first).
T0=$(date +%s)
setsid bash -c 'while IFS= read -r -d "" kv; do export "$kv"; done < "$1"; exec ./gradlew runClient --console=plain --args="--quickPlaySingleplayer CloudClean"' _ "$ENVF" \
  > "$OUT" 2>&1 < /dev/null &
echo "launched at $(date +%H:%M:%S); waiting for the first cloud draw..."

# 3. Wait for the first draw (or a build failure / crash).
drawn=0
for i in $(seq 1 100); do
  sleep 4
  if grep -qE "BUILD FAILED|error:" "$OUT"; then
    echo "FAIL: build failed"; grep -E "error:|\.java:[0-9]+|BUILD FAILED" "$OUT" | head -30; exit 1
  fi
  if [ -f "$LOG" ] && [ "$(stat -c %Y "$LOG")" -ge "$T0" ] && grep -q "first draw" "$LOG"; then drawn=1; break; fi
  if [ "$i" -gt 8 ] && ! pgrep -f "[K]notClient|[G]radleWrapperMain" >/dev/null; then
    echo "FAIL: client exited before drawing"; tail -40 "$OUT"; exit 1
  fi
done
if [ "$drawn" -ne 1 ]; then echo "FAIL: no cloud draw within 400s"; tail -30 "$OUT"; exit 1; fi
sleep 8   # let the world settle before judging

# 4. Log check: these must be absent. "unsupported uniform" means a shader uses a
#    uniform block the pipeline did not declare -- it is silently NOT bound.
grep "first draw" "$LOG" | tail -1
BAD=$(grep -iE "unsupported uniform|render pass failed|missing sampler|compil.*(error|fail)|simpleclouds.*(ERROR|Exception)" "$LOG" \
      | grep -vE "Unknown registry key|Could not find root Simple Clouds config" | head -20)

# 5. Screenshot of the whole desktop (the game window must be in front).
load_env
spectacle -b -n -o "$SHOT" >/dev/null 2>&1

if [ -n "$BAD" ]; then
  echo "FAIL: render warnings in the log:"; echo "$BAD"; echo "screenshot: $SHOT"; exit 1
fi

if [ "${1:-}" = "--install" ]; then
  ./gradlew build -x test --console=plain > "$HOME/.cache/simpleclouds/build.out" 2>&1 \
    && cp "$JAR" "$MODS/" && echo "installed $JAR into the real profile mods/" \
    || { echo "FAIL: jar build/install failed"; tail -20 "$HOME/.cache/simpleclouds/build.out"; exit 1; }
fi

echo "PASS (log clean). Now READ $SHOT and confirm what you changed is actually visible."

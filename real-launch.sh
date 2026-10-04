#!/usr/bin/env bash
# Launch Jan's REAL "Fabric 26.3" profile (full modpack incl. Distant
# Horizons) outside ModrinthApp, replicating Modrinth's own launch, so an agent
# can drive the in-game DevShot (a devshot.request in the profile dir) and then
# stop the game precisely by its unit name.
#
#   ./real-launch.sh "240 A B C D E F"    launch + queue the standard views
#   ./real-launch.sh "240 B LOOP FPSLOG"  launch + loop one view while logging FPS
#   ./real-launch.sh                      launch, no devshot
#
# Safety rules (see HANDOFF.md):
#  - refuses to start if ANY KnotClient is already running (Jan's Modrinth game
#    is a KnotClient too — never kill processes by that name);
#  - the game runs in its own user unit simpleclouds-realgame with a cgroup
#    memory ceiling, so a leak can only take the game down;
#  - stop it with:  systemctl --user stop simpleclouds-realgame
#  - the unit's stdout/stderr go to ~/.cache/simpleclouds/realgame.out and the
#    game log stays in the profile's logs/latest.log as usual.
set -u
cd "$(dirname "$0")"
# The host does not keep Python on the interactive PATH, but the classpath
# manifest parser below needs it. Re-enter through the system's Nix shell once
# so direct launches work exactly like launches from a prepared shell.
if ! command -v python3 >/dev/null 2>&1; then
	if ! command -v nix-shell >/dev/null 2>&1; then
		echo "FAIL (preflight): python3 and nix-shell are unavailable" >&2
		exit 1
	fi
	printf -v relaunch '%q ' "$(readlink -f "$0")" "$@"
	exec nix-shell -p python3 --run "$relaunch"
fi
PROFILE="$HOME/.local/share/ModrinthApp/profiles/Fabric 26.3"
META="$HOME/.local/share/ModrinthApp/meta"
ENVF="$HOME/.cache/simpleclouds/launch.env"   # session env of a known-good launch (display, Vulkan, libs)
OUT="$HOME/.cache/simpleclouds/realgame.out"
UNIT=simpleclouds-realgame
MCID="26.3-0.19.5"
# This isolated test world is a copy of the development flat-weather fixture.
# Never default to Jan's play world, and refuse a missing Quick Play identifier:
# Minecraft otherwise parks on a GUI error while the log appears to stall.
WORLD="${SC_WORLD:-CodexFullPackTest}"
WIDTH=1920
HEIGHT=1080
XMX="${SC_XMX:-6144m}"   # Modrinth global mc_memory_max=6144 MB (no instance override)
MEMORY_HIGH="${SC_MEMORY_HIGH:-12G}"
MEMORY_MAX="${SC_MEMORY_MAX:-14G}"
LOADER="0.19.5"

if [[ "$WORLD" == */* || "$WORLD" == *\\* || "$WORLD" == "." || "$WORLD" == ".." ]]; then
	echo "FAIL (preflight): invalid world name '$WORLD'" >&2
	exit 1
fi
QUICKPLAY="$WORLD"
if [[ "${SC_NEW_WORLD:-0}" == 1 ]]; then
	# Start on the title screen; DevShot CREATEWORLD/CREATEFLAT creates the world.
	# Never overwrite an existing save.
	if [[ -e "$PROFILE/saves/$WORLD" ]]; then
		echo "FAIL (preflight): SC_NEW_WORLD=1 but world '$WORLD' already exists" >&2
		exit 1
	fi
	QUICKPLAY=""
elif [[ ! -f "$PROFILE/saves/$WORLD/level.dat" ]]; then
	echo "FAIL (preflight): Quick Play world '$WORLD' has no level.dat in this profile's saves directory" >&2
	exit 1
fi

if systemctl --user is-active --quiet "$UNIT"; then
	echo "real game already running as unit $UNIT — refusing to launch a second one"; exit 1
fi
# (Match real java processes only, not any command line that happens to
# contain the string — a pgrep -f here matches our own tooling.)
for p in $(pgrep -x 'java|\.java-wrapped' 2>/dev/null); do
	if tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null | grep -q "net.fabricmc.loader.impl.launch.knot.KnotClient"; then
		echo "a KnotClient game (pid $p) is running that is not our unit — refusing (it may be Jan's Modrinth game)"; exit 1
	fi
done

# devshot request for this run (optional first argument).
bash tools/test-memory-budget.sh check || exit 1
rm -f "$PROFILE/devshot.request"
if [ -n "${1:-}" ]; then
	printf '%s\n' "$1" > "$PROFILE/devshot.request"
	echo "devshot request: $1"
fi

# classpath from the Modrinth version manifest (linux/x64 rules applied).
CP=$(python3 - "$META" "$MCID" "$LOADER" <<'EOF'
import json, sys, pathlib
meta, mcid, loader = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
v = json.load(open(meta / "versions" / mcid / (mcid + ".json")))
cp = [str(meta / "libraries" / f"net/fabricmc/fabric-loader/{loader}/fabric-loader-{loader}.jar")]
# The Fabric loader's jar bundles NEITHER org.objectweb.asm NOR the sponge
# mixin launcher classes; the launcher adds them (Knot fails in
# LoaderUtil.verifyClasspath / LoaderLibrary without them on the cp).
for a in ("asm", "asm-commons", "asm-tree", "asm-util", "asm-analysis"):
    p = meta / "libraries" / f"org/ow2/asm/{a}/9.10.1/{a}-9.10.1.jar"
    if p.exists():
        cp.append(str(p))
mix = meta / "libraries" / "net/fabricmc/sponge-mixin/0.17.4+mixin.0.8.7/sponge-mixin-0.17.4+mixin.0.8.7.jar"
if mix.exists():
    cp.append(str(mix))
cp.append(str(meta / "versions" / mcid / (mcid + ".jar")))
def applies(l):
    for r in l.get("rules", []):
        os_ = (r.get("os") or {}).get("name")
        arch = (r.get("os") or {}).get("arch")
        ok = os_ in (None, "linux") and arch in (None, "x86_64", "x64")
        if r.get("action") == "allow" and not ok:
            return False
        if r.get("action") == "disallow" and ok:
            return False
    return True
for l in v.get("libraries", []):
    if not l.get("include_in_classpath", False) or not applies(l):
        continue
    p = (l.get("downloads") or {}).get("artifact", {}).get("path")
    if p:
        cp.append(str(meta / "libraries" / p))
print(":".join(cp))
EOF
) || { echo "FAIL: could not build the classpath"; exit 1; }
echo "classpath: $(echo "$CP" | tr ':' '\n' | wc -l) jars"

NAT="$META/natives/$MCID/lwjgl/3.4.3-snapshot/x64"
ASSET_INDEX=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["assetIndex"]["id"])' "$META/versions/$MCID/$MCID.json") || exit 1
ASSETS="$META/assets"
JAVA="$META/java_versions/zulu25.36.205-ca-jre25.0.4.1-linux_x64/bin/java"

mkdir -p "$(dirname "$OUT")"
# SC_RAIN=1 forces vanilla rain on world join (--command is a client-main
# argument; the inner double quotes survive the systemd round-trip because
# they are baked into the single-quoted script text).
RAINARG=""
[ "${SC_RAIN:-0}" = "1" ] && RAINARG=' --command "weather rain"'
# NOTE: keep this to NINE or fewer positional parameters. systemd-run mangles
# double-digit ones in the ExecStart round-trip ($10 expands as $1+"0" — that
# silently turned --quickPlaySingleplayer into "$ENVF1" on 2026-09-13).
systemd-run --user --unit="$UNIT" --collect --quiet \
  --property=WorkingDirectory="$PROFILE" \
  --property=MemoryHigh="$MEMORY_HIGH" --property=MemoryMax="$MEMORY_MAX" \
  --property=TimeoutStopSec=8s --property=KillMode=control-group \
  --property=Environment=SIMPLECLOUDS_GPU_WORLD=${SIMPLECLOUDS_GPU_WORLD:-0} \
  --property=Environment=SIMPLECLOUDS_GPU_TEST_CPU_FALLBACK=${SIMPLECLOUDS_GPU_TEST_CPU_FALLBACK:-0} \
  --property=Environment=SIMPLECLOUDS_GPU_DIRECT_OPAQUE=${SIMPLECLOUDS_GPU_DIRECT_OPAQUE:-0} \
  --property=Environment=SIMPLECLOUDS_GPU_DIRECT_TRANSPARENT=${SIMPLECLOUDS_GPU_DIRECT_TRANSPARENT:-0} \
  --property=Environment=SIMPLECLOUDS_GPU_STORM_BITS=${SIMPLECLOUDS_GPU_STORM_BITS:-0} \
  --property=Environment=SIMPLECLOUDS_GPU_NO_READBACK=${SIMPLECLOUDS_GPU_NO_READBACK:-0} \
  --property=Environment=SIMPLECLOUDS_GPU_MAPPED_COUNTERS=${SIMPLECLOUDS_GPU_MAPPED_COUNTERS:-0} \
  --property=Environment=SIMPLECLOUDS_TRACE_VISUAL_CHURN=${SIMPLECLOUDS_TRACE_VISUAL_CHURN:-0} \
  --property=Environment=SIMPLECLOUDS_DEV=${SIMPLECLOUDS_DEV:-0} \
  --property=Environment=SIMPLECLOUDS_PROFILE=${SIMPLECLOUDS_PROFILE:-0} \
  --property=Environment=SIMPLECLOUDS_TEST_OIT_MRT=${SIMPLECLOUDS_TEST_OIT_MRT:-auto} \
  --property=Environment=SIMPLECLOUDS_TEST_OIT_MRT_PARITY=${SIMPLECLOUDS_TEST_OIT_MRT_PARITY:-0} \
  --property=Environment=SIMPLECLOUDS_TEST_NO_DH_DEFER=${SIMPLECLOUDS_TEST_NO_DH_DEFER:-0} \
  --property=Environment=SIMPLECLOUDS_TEST_UNCAPPED=${SIMPLECLOUDS_TEST_UNCAPPED:-0} \
  --property=Environment=SIMPLECLOUDS_TEST_COUNTER_SNAPSHOT=${SIMPLECLOUDS_TEST_COUNTER_SNAPSHOT:-0} \
  --property=Environment=SIMPLECLOUDS_TEST_COUNTER_EARLY_FLUSH=${SIMPLECLOUDS_TEST_COUNTER_EARLY_FLUSH:-0} \
  --property=Environment=SIMPLECLOUDS_TEST_COUNTER_RELOAD=${SIMPLECLOUDS_TEST_COUNTER_RELOAD:-0} \
  --property=Environment=SIMPLECLOUDS_TEST_DH_FOG_LIFECYCLE=${SIMPLECLOUDS_TEST_DH_FOG_LIFECYCLE:-0} \
  --property=Environment=SIMPLECLOUDS_TEST_NATIVE_BIOME_WEATHER=${SIMPLECLOUDS_TEST_NATIVE_BIOME_WEATHER:-0} \
  --property=Environment=SIMPLECLOUDS_TEST_WEATHER_LIBRARIES=${SIMPLECLOUDS_TEST_WEATHER_LIBRARIES:-0} \
  --property=Environment=SIMPLECLOUDS_TEST_FULLPACK_WEATHER_LIFECYCLE=${SIMPLECLOUDS_TEST_FULLPACK_WEATHER_LIFECYCLE:-0} \
  --property=Environment=SIMPLECLOUDS_TEST_WEATHER_SUSTAINED=${SIMPLECLOUDS_TEST_WEATHER_SUSTAINED:-0} \
  --property=Environment=SIMPLECLOUDS_TEST_WATER=${SIMPLECLOUDS_TEST_WATER:-0} \
  --property=Environment=SIMPLECLOUDS_TEST_ROOF=${SIMPLECLOUDS_TEST_ROOF:-0} \
  --property=Environment=SIMPLECLOUDS_TEST_NATIVE_RAIN_ROOF=${SIMPLECLOUDS_TEST_NATIVE_RAIN_ROOF:-0} \
  --property=Environment=SIMPLECLOUDS_TEST_PARTICLE_GPU=${SIMPLECLOUDS_TEST_PARTICLE_GPU:-0} \
  --property=Environment=SIMPLECLOUDS_TEST_GPU_READBACK=${SIMPLECLOUDS_TEST_GPU_READBACK:-0} \
  --property=Environment=SIMPLECLOUDS_TEST_APPEND_PROBE=${SIMPLECLOUDS_TEST_APPEND_PROBE:-0} \
  --property=Environment=SIMPLECLOUDS_TEST_ASYNC_GL_RESTORE=${SIMPLECLOUDS_TEST_ASYNC_GL_RESTORE:-0} \
  --expand-environment=no \
  bash -c 'live_display=${DISPLAY-}; live_auth=${XAUTHORITY-}
  live_wayland=${WAYLAND_DISPLAY-}; live_runtime=${XDG_RUNTIME_DIR-}
  while IFS= read -r -d "" kv; do export "$kv"; done < "$1"
  export DISPLAY="$live_display" XAUTHORITY="$live_auth"
  export WAYLAND_DISPLAY="$live_wayland" XDG_RUNTIME_DIR="$live_runtime"
  bash "$HOME/simple-clouds-fabric/tools/test-memory-budget.sh" watch simpleclouds-realgame &
  exec "$2" -Xmx"$3" -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=50 \
    -Djava.library.path="$4" \
    -cp "$5" net.fabricmc.loader.impl.launch.knot.KnotClient \
    --gameDir "$6" --assetsDir "$7" --assetIndex "$9" \
    --username Bozmund --uuid 90d6544a-1f2d-4f6f-a0ab-209215c4207e \
    --userType mojang --version 26.3-0.19.5 --versionType release --gameVersion 26.3 \
    --accessToken offline --clientId "" \
    --width 1920 --height 1080 ${8:+--quickPlaySingleplayer "$8"}'"$RAINARG"\
  _ "$ENVF" "$JAVA" "$XMX" "$NAT" "$CP" "$PROFILE" "$ASSETS" "$QUICKPLAY" "$ASSET_INDEX" \
  > "$OUT" 2>&1 \
  || { echo "FAIL (launch): could not start user unit $UNIT"; exit 1; }
echo "launched the real profile as user unit $UNIT (java log: $OUT, game log: $PROFILE/logs/latest.log)"
echo "stop: systemctl --user stop $UNIT"

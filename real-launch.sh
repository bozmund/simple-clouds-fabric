#!/usr/bin/env bash
# Launch Jan's REAL "Fabric 26.2" profile (full 327-mod modpack incl. Distant
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
PROFILE="$HOME/.local/share/ModrinthApp/profiles/Fabric 26.2"
META="$HOME/.local/share/ModrinthApp/meta"
ENVF="$HOME/.cache/simpleclouds/launch.env"   # session env of a known-good launch (display, Vulkan, libs)
OUT="$HOME/.cache/simpleclouds/realgame.out"
UNIT=simpleclouds-realgame
MCID="26.2-0.19.5"
# Step 7 used the lightweight test world: the main "New World" (Jan's play world)
# worldgen OOMs and takes ~8 min to join; "New World (1)" has the identical modpack
# and the STORM scene is self-contained (spawns its own formation 2000 blocks north
# of the camera), so the world does not change the cloud-cost / playability result.
WORLD="New World (1)"
WIDTH=1920
HEIGHT=1080
XMX="${SC_XMX:-6144m}"   # Modrinth global mc_memory_max=6144 MB (no instance override)
LOADER="0.19.5"

if systemctl --user is-active --quiet "$UNIT"; then
	echo "real game already running as unit $UNIT — refusing to launch a second one"; exit 1
fi
# (Match real java processes only, not any command line that happens to
# contain the string — a pgrep -f here matches our own tooling.)
for p in $(pgrep -x java 2>/dev/null); do
	if tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null | grep -q "net.fabricmc.loader.impl.launch.knot.KnotClient"; then
		echo "a KnotClient game (pid $p) is running that is not our unit — refusing (it may be Jan's Modrinth game)"; exit 1
	fi
done

# devshot request for this run (optional first argument).
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

NAT="$META/natives/$MCID/lwjgl/3.4.1-snapshot/x64"
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
  --property=MemoryHigh=12G --property=MemoryMax=14G \
  bash -c 'while IFS= read -r -d "" kv; do export "$kv"; done < "$1"
  exec "$2" -Xmx"$3" -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=50 \
    -Djava.library.path="$4" \
    -cp "$5" net.fabricmc.loader.impl.launch.knot.KnotClient \
    --gameDir "$6" --assetsDir "$7" --assetIndex 32 \
    --username Bozmund --uuid 90d6544a-1f2d-4f6f-a0ab-209215c4207e \
    --userType mojang --version 26.2-0.19.5 --versionType release --gameVersion 26.2 \
    --accessToken offline --clientId "" \
    --width 1920 --height 1080 --quickPlaySingleplayer "$8"'"$RAINARG"\
  _ "$ENVF" "$JAVA" "$XMX" "$NAT" "$CP" "$PROFILE" "$ASSETS" "$WORLD" \
  > "$OUT" 2>&1 \
  || { echo "FAIL (launch): could not start user unit $UNIT"; exit 1; }
echo "launched the real profile as user unit $UNIT (java log: $OUT, game log: $PROFILE/logs/latest.log)"
echo "stop: systemctl --user stop $UNIT"

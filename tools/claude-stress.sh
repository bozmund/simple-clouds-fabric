#!/usr/bin/env bash
# Claude, 2026-09-15: handoff item 7 -- 30-minute LOOP stress test of the dev client.
#   claude-stress.sh [seconds]   (default 1800)
# Starts "A B C D F LOOP" through ./dev-relaunch.sh (which returns after the first 5 shots and leaves
# the client looping), samples every 30 s (RSS of the dev-client java, LOOP cycles, the mod's own
# [DEVMEM] line) into <evidence>/samples.tsv, then ends the loop by deleting run/devshot.request (the
# mod's stop switch) and stops ONLY the simpleclouds-devclient unit. Stops early if Jan's real game
# (a java/minecraft process outside the dev-client unit) appears or the client dies. Installs nothing.
C=$(cd "$(dirname "$0")/.." && pwd)
cd "$C" || exit 1
DUR=${1:-1800}
E="$C/evidence-claude-0915-40-stress"
L="$C/claude-batch-0915.log"
LOG=run/logs/latest.log
mkdir -p "$E"
log() { echo "$(date +%T) $*" >>"$L"; }
unit_java() { for p in $(pgrep -x java); do grep -q simpleclouds-devclient "/proc/$p/cgroup" 2>/dev/null && echo "$p"; done; }
real_game() {
	for p in $(pgrep -x java); do
		grep -q simpleclouds-devclient "/proc/$p/cgroup" 2>/dev/null && continue
		tr '\0' ' ' <"/proc/$p/cmdline" 2>/dev/null | grep -qi minecraft && echo "$p"
	done
}
finish() {
	cp -p "$LOG" "$E/latest.log" 2>/dev/null
	cp -p run/screenshots/devshot-[A-F].png "$E/" 2>/dev/null
	rm -f run/devshot.request
	sleep 5
	systemctl --user stop simpleclouds-devclient
	sleep 2
	log "stress: devclient $(systemctl --user is-active simpleclouds-devclient)"
}

echo "BATCH START $(date '+%F %T')  jar $(sha256sum build/libs/simple-clouds-0.7.3+26.2-fabric.jar | cut -c1-16)  stress ${DUR}s" >>"$L"
if [ -n "$(real_game)" ]; then log "stress: real game running - not starting"; echo "BATCH DONE $(date '+%F %T') (not started)" >>"$L"; exit 1; fi
DEVSHOT_EXTRA="A B C D F LOOP" ./dev-relaunch.sh >"$E/dev-relaunch.out" 2>&1
rc=$?
log "stress: dev-relaunch exit $rc ($(grep -c '^CAPTURED' "$E/dev-relaunch.out") captured)"
if [ $rc -ne 0 ]; then finish; echo "BATCH DONE $(date '+%F %T') (dev-relaunch failed)" >>"$L"; exit 1; fi

T0=$(date +%s)
END_REASON="time"
printf 'time\telapsed_s\trss_gb\tloop_cycles\tdevmem\n' >"$E/samples.tsv"
while [ $(($(date +%s) - T0)) -lt "$DUR" ]; do
	sleep 30
	if [ -n "$(real_game)" ]; then END_REASON="real game started"; log "stress: real game started - stopping early"; break; fi
	if ! systemctl --user is-active -q simpleclouds-devclient; then END_REASON="dev client stopped by itself"; log "stress: dev client stopped by itself"; break; fi
	jp=$(unit_java | head -1)
	rss=$(awk '/VmRSS/ { printf "%.2f", $2 / 1048576 }' "/proc/$jp/status" 2>/dev/null)
	cycles=$(grep -c 'LOOP cycle' "$LOG")
	dm=$(grep -o '\[DEVMEM\].*' "$LOG" | tail -1)
	printf '%s\t%s\t%s\t%s\t%s\n' "$(date +%T)" "$(($(date +%s) - T0))" "$rss" "$cycles" "$dm" >>"$E/samples.tsv"
done
log "stress: ended after $(($(date +%s) - T0)) s ($END_REASON)"
finish
{
	echo "end reason: $END_REASON, sampled $(($(wc -l <"$E/samples.tsv") - 1)) times, loop cycles $(grep -c 'LOOP cycle' "$E/latest.log")"
	echo "== transform capacity max: $(grep -o 'Dynamic Transforms UBO.*New capacity will be [0-9]*' "$E/latest.log" | grep -o '[0-9]*$' | sort -n | tail -1)"
	echo "== generation summaries (failed / worstFrameMs / transformWritesPeakPerFrame / pending / staged):"
	grep -o 'Simple Clouds generation backend=CPU.*' "$E/latest.log" | grep -o 'failed=[0-9]*\|worstFrameMs=[0-9]*\|transformWritesPeakPerFrame=[0-9]*\|pending=[0-9]*\|staged=[0-9]*' | paste -d' ' - - - - -
	echo "== DEVMEM:"; grep -o '\[DEVMEM\].*' "$E/latest.log"
	echo "== errors / exceptions:"; grep -iE 'simpleclouds.*(ERROR|Exception)|OutOfMemory|cap exceeded|render pass failed|refused' "$E/latest.log" | cut -c1-220 | sort | uniq -c | head -20
} >"$E/summary.txt"
P='(builtins.getFlake "path:/home/jan/nixos-symbiote").inputs.nixpkgs.legacyPackages.x86_64-linux'
(cd "$E" && ls devshot-*.png >/dev/null 2>&1 && nix shell --impure --expr "$P.python3.withPackages (ps: [ ps.pillow ])" \
	-c python3 "$C/tools/sheet.py" 'devshot-*.png' "$E/contact-sheet.png" >/dev/null 2>&1)
echo "BATCH DONE $(date '+%F %T') ($END_REASON)" >>"$L"

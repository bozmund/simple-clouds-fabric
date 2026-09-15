#!/usr/bin/env bash
# Claude, 2026-09-15: is the STORM scene repeatable after the fixed-size / fixed-angle fixture?
# Two identical STORM runs + one STORM NOFOG, one after another, via tools/claude-run.sh
# (refuses while the real game runs, stops only simpleclouds-devclient). Log: claude-batch-0915.log.
C=$(cd "$(dirname "$0")/.." && pwd)
cd "$C" || exit 1
L="$C/claude-batch-0915.log"
run() {
	echo "===== $(date +%T) start: $*" >>"$L"
	bash tools/claude-run.sh "$@" >>"$L" 2>&1
	echo "===== $(date +%T) exit $?: $1" >>"$L"
}
echo "BATCH START $(date '+%F %T')  jar $(sha256sum build/libs/simple-clouds-0.7.3+26.2-fabric.jar | cut -c1-16)" >>"$L"
run evidence-claude-0915-20-storm-a     STORM
run evidence-claude-0915-21-storm-b     STORM
run evidence-claude-0915-22-storm-nofog STORM NOFOG
echo "BATCH DONE $(date '+%F %T')" >>"$L"

#!/usr/bin/env bash
# Claude, 2026-09-15: new oblique S4 view -- one STORM and one STORM NOFOG run (matched pair),
# via tools/claude-run.sh (refuses while the real game runs, stops only simpleclouds-devclient).
C=/home/jan/.cache/simpleclouds/port-candidate-tgkFnnmU
cd "$C" || exit 1
L="$C/claude-batch-0915.log"
run() {
	echo "===== $(date +%T) start: $*" >>"$L"
	bash tools/claude-run.sh "$@" >>"$L" 2>&1
	echo "===== $(date +%T) exit $?: $1" >>"$L"
}
echo "BATCH START $(date '+%F %T')  jar $(sha256sum build/libs/simple-clouds-0.7.3+26.2-fabric.jar | cut -c1-16)" >>"$L"
run evidence-claude-0915-30-s4-storm STORM
run evidence-claude-0915-31-s4-nofog STORM NOFOG
echo "BATCH DONE $(date '+%F %T')" >>"$L"

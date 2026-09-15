#!/usr/bin/env bash
# Claude, 2026-09-15: handoff step 1 -- re-run the comparison tests with Codex's settle gate,
# one after another. Each run goes through tools/claude-run.sh (refuses while the real game runs,
# stops only simpleclouds-devclient). Progress: claude-batch-0915.log in the candidate folder.
C=$(cd "$(dirname "$0")/.." && pwd)
cd "$C" || exit 1
L="$C/claude-batch-0915.log"
run() {
	echo "===== $(date +%T) start: $*" >>"$L"
	bash tools/claude-run.sh "$@" >>"$L" 2>&1
	echo "===== $(date +%T) exit $?: $1" >>"$L"
}
echo "BATCH START $(date '+%F %T')  jar $(sha256sum build/libs/simple-clouds-0.7.3+26.2-fabric.jar | cut -c1-16)" >>"$L"
run evidence-claude-0915-10-nofog     STORM NOFOG
run evidence-claude-0915-11-hideflash STORM NOFOG HIDEFLASH
run evidence-claude-0915-12-fogdebug  STORM FOGDEBUG
run evidence-claude-0915-13-shakefast SHAKE FAST
run evidence-claude-0915-14-abcdf     A B C D F
echo "BATCH DONE $(date '+%F %T')" >>"$L"

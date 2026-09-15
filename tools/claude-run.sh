#!/usr/bin/env bash
# One isolated dev test for the Simple Clouds candidate (Claude, 2026-09-15):
#   claude-run.sh <evidence-name> <DEVSHOT tokens...>
# Refuses while Jan's real game runs; runs ./dev-relaunch.sh with the tokens; saves the log and
# screenshots into <candidate>/<evidence-name>/ with a contact sheet; prints the summary numbers;
# then stops ONLY the simpleclouds-devclient unit. Never installs anything.
set -u
C=$(cd "$(dirname "$0")/.." && pwd)
cd "$C" || exit 1
NAME="$1"; shift
TOKENS="$*"
G=$(ps -C java -o pid=,args= | grep -i minecraft)
if [ -n "$G" ]; then echo "real game running - not launching:"; echo "$G" | cut -c1-160; exit 1; fi
T0=$(date +%s)
DEVSHOT_EXTRA="$TOKENS" ./dev-relaunch.sh > /tmp/claude-run.out 2>&1
rc=$?
echo "dev-relaunch ($TOKENS): exit $rc after $(( $(date +%s) - T0 )) s"
grep -v '^CAPTURED' /tmp/claude-run.out | tail -6
echo "captured: $(grep -c '^CAPTURED' /tmp/claude-run.out)"
E="$C/$NAME"; mkdir -p "$E"
L=run/logs/latest.log
cp -p "$L" "$E/latest.log"; cp -p /tmp/claude-run.out "$E/dev-relaunch.out"
cp -p run/screenshots/devshot-*.png "$E/" 2>/dev/null
echo "==== summaries"; grep -o 'Simple Clouds generation backend=CPU.*' "$L" | sed 's/stale\[[^]]*\] //' | cut -c1-200
echo "==== devmem"; grep -o '\[DEVMEM\].*' "$L" | tail -3
echo "==== transforms max capacity: $(grep -o 'Dynamic Transforms UBO.*New capacity will be [0-9]*' "$L" | grep -o '[0-9]*$' | sort -n | tail -1)"
echo "==== gate / terrain"; grep -o '\[DEVSHOT\] devshot-[^ ]*: terrain.*' "$L" | cut -c1-170
echo "==== simpleclouds errors"; grep -i -E 'simpleclouds.*(ERROR|Exception)|cap exceeded|0:[0-9]+\([0-9]+\): error|unsupported uniform|render pass failed|missing sampler' "$L" | cut -c1-220 | head -8
systemctl --user stop simpleclouds-devclient; sleep 2
echo "devclient: $(systemctl --user is-active simpleclouds-devclient)"
P='(builtins.getFlake "path:/home/jan/nixos-symbiote").inputs.nixpkgs.legacyPackages.x86_64-linux'
cd "$E" && ls devshot-*.png >/dev/null 2>&1 && nix shell --impure --expr "$P.python3.withPackages (ps: [ ps.pillow ])" \
  -c python3 "$C/tools/sheet.py" 'devshot-*.png' "$E/contact-sheet.png" 2>&1 | grep -v 'Nix search'
exit $rc

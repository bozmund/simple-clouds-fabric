#!/usr/bin/env bash
# Sample the real game's RSS once per minute for up to N minutes (default 32),
# appending "<epoch> <rss_kb>" lines to the output file. Stops early if the
# java process or the unit disappears.
OUT="${1:-$HOME/.cache/simpleclouds/rss-loop.log}"
MINUTES="${2:-32}"
: > "$OUT"
for i in $(seq 1 "$MINUTES"); do
	p=$(pgrep -x java | head -n 1)
	if [ -z "$p" ] || ! systemctl --user is-active --quiet simpleclouds-realgame 2>/dev/null; then
		echo "$(date +%s) GAME_GONE after ${i} samples" >> "$OUT"
		exit 1
	fi
	rss=$(ps -o rss= -p "$p" | tr -d '[:space:]')
	echo "$(date +%s) $rss" >> "$OUT"
	sleep 60
done
echo "sampler done: $MINUTES samples"

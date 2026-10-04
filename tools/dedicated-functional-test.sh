#!/usr/bin/env bash
# User-approved disposable localhost server. Never changes EULA implicitly.
set -euo pipefail
cd /home/jan/simple-clouds-fabric
unit=simpleclouds-servercheck
root="$PWD/build/dedicated-boundary-smoke"
log="$root/logs/latest.log"
[[ -f "$root/eula.txt" ]] && grep -qx 'eula=true' "$root/eula.txt"
grep -qx 'server-ip=127.0.0.1' "$root/server.properties"
grep -qx 'server-port=25575' "$root/server.properties"
grep -qx 'pause-when-empty-seconds=-1' "$root/server.properties"
for other in "$unit" simpleclouds-devclient simpleclouds-realgame simpleclouds-refclient; do
  if systemctl --user is-active --quiet "$other"; then echo "Concurrent owned test: $other" >&2; exit 1; fi
done
[[ -z "$(ss -ltnH '( sport = :25575 )')" ]]
bash tools/test-memory-budget.sh check
evidence=$(mktemp -d "$PWD/evidence-codex-20261001-dedicated-functional-XXXXXX")
fifo="$evidence/console.fifo"
mkfifo -m 600 "$fifo"
started=0
cleanup() {
  result=$?; trap - EXIT
  if [[ "$started" == 1 ]]; then
    if systemctl --user is-active --quiet "$unit"; then systemctl --user stop "$unit" || true; fi
    journalctl --user -u "$unit" --since "@$started_at" --no-pager > "$evidence/journal.log" || true
    [[ ! -f "$log" ]] || cp "$log" "$evidence/latest.log"
  fi
  printf 'Result=%s Evidence=%s\n' "$result" "$evidence"
  exit "$result"
}
trap cleanup EXIT
started_at=$(date +%s)
systemd-run --user --unit="$unit" --collect --quiet \
  --property=WorkingDirectory="$PWD" --property=MemoryHigh=3G --property=MemoryMax=4G \
  --property=TimeoutStopSec=30s --property=KillMode=control-group --expand-environment=no \
  bash -c 'exec 3<>"$1"; while IFS= read -r -d "" kv; do export "$kv"; done < /home/jan/.cache/simpleclouds/launch.env; exec ./gradlew --offline --no-daemon --max-workers=4 -I tools/dedicated-boundary-smoke.gradle runServer --args=nogui --console=plain <&3' _ "$fifo"
started=1
wait_for() {
  local pattern=$1 deadline=$((SECONDS+120))
  until [[ -f "$log" ]] && grep -q "$pattern" "$log" && [[ $(stat -c %Y "$log") -ge $started_at ]]; do
    if (( SECONDS >= deadline )); then echo "Timeout: $pattern" >&2; return 1; fi
    systemctl --user is-active --quiet "$unit" || { echo 'Server stopped early' >&2; return 1; }
    sleep 1
  done
}
wait_for 'Done ('
ss -ltnH '( sport = :25575 )' | tee "$evidence/listen.txt"
grep -q '127.0.0.1.*25575' "$evidence/listen.txt"
while IFS= read -r -d '' kv; do export "$kv"; done < /home/jan/.cache/simpleclouds/launch.env
java -cp "$(cat build/parity-classpath.txt)" EditDedicatedTestConfig.java true
wait_for 'Reloaded world server config'
cp "$log" "$evidence/changed.log"
java -cp "$(cat build/parity-classpath.txt)" EditDedicatedTestConfig.java false
deadline=$((SECONDS+20))
until [[ $(grep -c 'Reloaded world server config' "$log") -ge 2 ]]; do
  (( SECONDS < deadline )) || { echo 'Second config reload missing' >&2; exit 1; }
  sleep 1
done
printf 'stop\n' > "$fifo"
wait_for 'Unloaded world server config .*loaded=false'
deadline=$((SECONDS+30))
while systemctl --user is-active --quiet "$unit"; do
  (( SECONDS < deadline )) || { echo 'Normal shutdown timed out' >&2; exit 1; }
  sleep 1
done
grep -q 'Stopping server' "$log"
[[ -z "$(ss -ltnH '( sport = :25575 )')" ]]
echo 'PASS: localhost startup, two live config reloads and normal saved shutdown/unbinding'

#!/usr/bin/env bash
# Private scratch clients + accepted localhost server; never uses play profiles.
set -euo pipefail
cd /home/jan/simple-clouds-fabric
project=$(pwd -P)
count=${1:-1}
[[ "$count" == 1 || "$count" == 2 ]] || exit 2
mode=${2:-sync}
weather_connection=${SIMPLECLOUDS_TEST_WEATHER_CONNECTION:-0}
[[ "$weather_connection" == 0 || "$weather_connection" == 1 ]] || exit 2
[[ "$weather_connection" == 0 || "$mode" == sync ]] || { echo 'Weather connection fixture requires sync mode' >&2; exit 2; }
case "$mode" in sync) sleep_test=0; sync_test=1;; sleep) sleep_test=1; sync_test=0; [[ "$count" == 2 ]] || exit 2;; *) exit 2;; esac
server=simpleclouds-servercheck
root="$project/build/dedicated-boundary-smoke"
log="$root/logs/latest.log"
grep -qx 'eula=true' "$root/eula.txt"
grep -qx 'server-ip=127.0.0.1' "$root/server.properties"
grep -qx 'server-port=25575' "$root/server.properties"
grep -qx 'pause-when-empty-seconds=-1' "$root/server.properties"
for unit in "$server" simpleclouds-devclient simpleclouds-realgame simpleclouds-refclient; do
  if systemctl --user is-active --quiet "$unit"; then echo "Concurrent owned test: $unit" >&2; exit 1; fi
done
[[ -z "$(ss -ltnH '( sport = :25575 )')" ]]
bash tools/test-memory-budget.sh check
evidence=$(mktemp -d "$project/evidence-codex-20261001-dedicated-multiplayer-XXXXXX")
fifo="$evidence/console.fifo"
mkfifo -m 600 "$fifo"
started_at=$(date +%s)
reload_fixture=0
labels=(A); units=(simpleclouds-devclient)
if [[ "$count" == 2 ]]; then labels+=(B); units+=(simpleclouds-refclient); fi
cleanup() {
  result=$?; trap - EXIT
  if [[ "$reload_fixture" == 1 ]] && systemctl --user is-active --quiet "$server"; then
    timeout 3 bash -c 'printf "datapack disable \"file/codex-cloud-types\"\n" > "$1"' _ "$fifo" || true
  fi
  for unit in "${units[@]}" "$server"; do
    if systemctl --user is-active --quiet "$unit"; then systemctl --user stop "$unit" || true; fi
    journalctl --user -u "$unit" --since "@$started_at" --no-pager > "$evidence/$unit.journal.log" || true
  done
  [[ ! -f "$log" ]] || cp "$log" "$evidence/server.log"
  for label in "${labels[@]}"; do
    [[ ! -f "build/dedicated-client-$label/logs/latest.log" ]] || cp "build/dedicated-client-$label/logs/latest.log" "$evidence/client-$label.log"
  done
  printf 'Result=%s Evidence=%s\n' "$result" "$evidence"
  exit "$result"
}
trap cleanup EXIT
trap 'exit 143' TERM
trap 'exit 130' INT
[[ ! -f "$root/whitelist.json" ]] || cp "$root/whitelist.json" "$evidence/whitelist-before.json"
while IFS= read -r -d '' kv; do export "$kv"; done < /home/jan/.cache/simpleclouds/launch.env
cd "$project"
java DedicatedWhitelistFixture.java
java -cp "$(cat build/parity-classpath.txt)" EditDedicatedTestConfig.java false
systemd-run --user --unit="$server" --collect --quiet --expand-environment=no \
  --property=WorkingDirectory="$root" --property=MemoryHigh=3G --property=MemoryMax=4G --property=KillMode=control-group \
  --property="Environment=SIMPLECLOUDS_TEST_SLEEP=$sleep_test" \
  bash -c 'exec 3<>"$1"; while IFS= read -r -d "" kv; do export "$kv"; done < /home/jan/.cache/simpleclouds/launch.env; exec java -Xmx2G -XX:MaxDirectMemorySize=1G @/home/jan/simple-clouds-fabric/build/dedicated-launch/runServer.args nogui <&3' _ "$fifo"
wait_log() {
  local file=$1 pattern=$2 deadline=$((SECONDS+180))
  until [[ -f "$file" ]] && grep -q "$pattern" "$file" && [[ $(stat -c %Y "$file") -ge $started_at ]]; do
    (( SECONDS < deadline )) || { echo "Timeout: $pattern in $file" >&2; return 1; }
    systemctl --user is-active --quiet "$server" || { echo 'Server stopped early' >&2; return 1; }
    if [[ -f "$log" ]] && [[ $(stat -c %Y "$log") -ge $started_at ]] && grep -q 'Simple Clouds ERROR' "$log"; then echo 'Server error gate' >&2; return 1; fi
    for label in "${labels[@]}"; do
      if [[ -f "build/dedicated-client-$label/logs/latest.log" ]] && [[ $(stat -c %Y "build/dedicated-client-$label/logs/latest.log") -ge $started_at ]] && grep -q 'Simple Clouds ERROR' "build/dedicated-client-$label/logs/latest.log"; then echo 'Client error gate' >&2; return 1; fi
    done
    available=$(awk '/MemAvailable:/ {print $2}' /proc/meminfo)
    (( available > 2*1024*1024 )) || { echo 'Host memory guard' >&2; return 1; }
    sleep 1
  done
}
wait_log "$log" 'Done ('
ss -ltnH '( sport = :25575 )' > "$evidence/listen.txt"
grep -q '127.0.0.1.*25575' "$evidence/listen.txt"
printf 'simpleclouds clouds speed set 1\nsimpleclouds clouds height set 128\n' > "$fifo"
for index in "${!labels[@]}"; do
  label=${labels[$index]}; unit=${units[$index]}
  client="$project/build/dedicated-client-$label"
  mkdir -p "$client"
  [[ ! -f "$client/options.txt" ]] || cp "$client/options.txt" "$evidence/client-$label-options-before.txt"
  # Reuse established test options to avoid the new-install accessibility
  # onboarding screen intercepting Quick Play. Source options stay untouched.
  cp run/options.txt "$client/options.txt"
  # Existing generated scratch state is preserved; no mods or play saves copied.
  systemd-run --user --unit="$unit" --collect --quiet --expand-environment=no \
    --property="WorkingDirectory=$client" --property=MemoryHigh=5G --property=MemoryMax=6G --property=KillMode=control-group \
    --property=Environment=SIMPLECLOUDS_DEV=1 --property="Environment=SIMPLECLOUDS_TEST_DEDICATED=$sync_test" \
    --property="Environment=SIMPLECLOUDS_TEST_SLEEP=$sleep_test" \
    --property="Environment=SIMPLECLOUDS_TEST_WEATHER_CONNECTION=$weather_connection" \
    bash -c 'live_display=${DISPLAY-}; live_auth=${XAUTHORITY-}; live_wayland=${WAYLAND_DISPLAY-}; live_runtime=${XDG_RUNTIME_DIR-}; while IFS= read -r -d "" kv; do export "$kv"; done < /home/jan/.cache/simpleclouds/launch.env; export DISPLAY="$live_display" XAUTHORITY="$live_auth" WAYLAND_DISPLAY="$live_wayland" XDG_RUNTIME_DIR="$live_runtime"; exec java @/home/jan/simple-clouds-fabric/build/dedicated-launch/runClient.args --gameDir "$1" --username "Codex$2" --uuid "00000000-0000-0000-0000-00000000000$3" --width 800 --height 450 --quickPlayMultiplayer 127.0.0.1:25575' _ "$client" "$label" "$((index+1))"
  if [[ "$weather_connection" == 1 ]]; then
    # A failed prior dimension test may have saved this private identity in the
    # Nether. Normalize its real server dimension, never edit/delete player data.
    wait_log "$log" "Codex$label joined the game"
    printf 'execute in minecraft:overworld run tp Codex%s 0 100 0\n' "$label" > "$fifo"
  fi
  if [[ "$mode" == sync ]]; then wait_log "$client/logs/latest.log" '\[DEDICATED-PROBE\] joined:'; fi
done
if [[ "$mode" == sleep ]]; then
  wait_log "$log" '\[SLEEP-PROBE\] PASS:'
  for label in "${labels[@]}"; do
    wait_log "build/dedicated-client-$label/logs/latest.log" '\[SLEEP-CLIENT\] nearby storm removal synchronized'
  done
else
pack_was_present=0
[[ ! -f "$root/CodexDedicatedServer20261001/datapacks/codex-cloud-types/pack.mcmeta" ]] || pack_was_present=1
java DedicatedDatapackFixture.java
reload_fixture=1
printf 'reload\n' > "$fifo"
# A first /reload discovers a new pack; on repeated runs it remains explicitly
# disabled from the previous successful cleanup and needs enabling instead.
if [[ "$pack_was_present" == 1 ]]; then printf 'datapack enable "file/codex-cloud-types"\n' > "$fifo"; fi
wait_log "$log" 'Loaded 10 cloud types'
for label in "${labels[@]}"; do wait_log "build/dedicated-client-$label/logs/latest.log" '\[DEDICATED-PROBE\] reload added both nested cloud types;'; done
printf 'datapack disable "file/codex-cloud-types"\n' > "$fifo"
for label in "${labels[@]}"; do wait_log "build/dedicated-client-$label/logs/latest.log" '\[DEDICATED-PROBE\] reload removed scratch types;'; done
reload_fixture=0
first="build/dedicated-client-A/logs/latest.log"
speed=$(sed -n 's/.*joined:.* speed=\([0-9.eE+-]*\) height=.*/\1/p' "$first" | tail -n 1)
height=$(sed -n 's/.*joined:.* height=\([0-9]*\) types=.*/\1/p' "$first" | tail -n 1)
[[ "$speed" =~ ^[0-9.eE+-]+$ && "$height" =~ ^[0-9]+$ ]]
if [[ "$count" == 2 ]]; then
  seedA=$(sed -n 's/.*joined: seed=\([-0-9]*\) speed=.*/\1/p' "$first" | tail -n 1)
  seedB=$(sed -n 's/.*joined: seed=\([-0-9]*\) speed=.*/\1/p' build/dedicated-client-B/logs/latest.log | tail -n 1)
  [[ "$seedA" == "$seedB" && -n "$seedA" ]] || { echo 'Client seeds differ' >&2; exit 1; }
fi
printf 'simpleclouds clouds speed set 0.37\nsimpleclouds clouds height set 1234\nsimpleclouds config server set whitelistAsBlacklist true\n' > "$fifo"
# Allow the server tick to transfer weather authority before issuing /weather;
# issuing it while custom weather still owns the dimension is correctly rejected.
sleep 2
printf 'time set 12000\nweather rain\n' > "$fifo"
for label in "${labels[@]}"; do wait_log "build/dedicated-client-$label/logs/latest.log" '\[DEDICATED-PROBE\] changed:'; done
printf 'simpleclouds clouds speed set %s\nsimpleclouds clouds height set %s\ntime set 1000\nweather clear\nsimpleclouds config server set whitelistAsBlacklist false\n' "$speed" "$height" > "$fifo"
if [[ "$weather_connection" == 1 ]]; then
  for label in "${labels[@]}"; do
    wait_log "build/dedicated-client-$label/logs/latest.log" '\[WEATHER-DIMENSION\] armed old Overworld state;'
    printf 'gamemode spectator Codex%s\nexecute in minecraft:the_nether run tp Codex%s 0 100 0\n' "$label" "$label" > "$fifo"
  done
  for label in "${labels[@]}"; do
    wait_log "build/dedicated-client-$label/logs/latest.log" '\[WEATHER-DIMENSION\] PASS dimension=minecraft:the_nether'
    printf 'execute in minecraft:overworld run tp Codex%s 0 100 0\n' "$label" > "$fifo"
  done
  for label in "${labels[@]}"; do
    wait_log "build/dedicated-client-$label/logs/latest.log" '\[WEATHER-DIMENSION\] PASS dimension=minecraft:overworld'
  done
fi
for label in "${labels[@]}"; do wait_log "build/dedicated-client-$label/logs/latest.log" '\[DEDICATED-PROBE\] disconnect cleared snapshot; PASS'; done
if [[ "$weather_connection" == 1 ]]; then
  for label in "${labels[@]}"; do
    wait_log "build/dedicated-client-$label/logs/latest.log" '\[WEATHER-CONNECTION\] PASS real disconnect=2 ownedEntries=0 ownedWorld=null historyEntries=0 historyWorld=null activeCounter=0'
  done
fi
fi
printf 'stop\n' > "$fifo"
wait_log "$log" 'Unloaded world server config .*loaded=false'
deadline=$((SECONDS+30))
while systemctl --user is-active --quiet "$server"; do
  (( SECONDS < deadline )) || { echo 'Normal shutdown timed out' >&2; exit 1; }
  sleep 1
done
[[ -z "$(ss -ltnH '( sport = :25575 )')" ]]
echo "PASS: $count dedicated client(s), mode=$mode and server saved shutdown"

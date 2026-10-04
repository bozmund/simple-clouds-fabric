#!/usr/bin/env bash
set -euo pipefail
cd /home/jan/simple-clouds-fabric
for pid in $(pgrep -x 'java|\.java-wrapped' 2>/dev/null || true); do
  if tr '\0' ' ' < "/proc/$pid/cmdline" | grep -q KnotClient; then echo "Refusing concurrent Minecraft PID=$pid"; exit 1; fi
done
while IFS= read -r -d '' kv; do export "$kv"; done < /home/jan/.cache/simpleclouds/launch.env
cd /home/jan/simple-clouds-fabric
while IFS= read -r kv; do
  case "$kv" in DISPLAY=*|XAUTHORITY=*|WAYLAND_DISPLAY=*|XDG_RUNTIME_DIR=*) export "$kv";; esac
done < <(systemctl --user show-environment)
unset DRI_PRIME
./gradlew --offline --no-daemon --max-workers=4 -q -I tools/test-classpath.gradle classes writeParityClasspath
timeout 180s java -Xmx256m --enable-native-access=ALL-UNNAMED \
  -cp "$(<build/parity-classpath.txt)" OriginalMeshAllocationParityTest.java

#!/usr/bin/env bash
set -euo pipefail
cd "$(cd "$(dirname "$0")/.." && pwd)"
test_project=$(pwd -P)
for pid in $(pgrep -x 'java|\.java-wrapped' 2>/dev/null || true); do
  if tr '\0' ' ' < "/proc/$pid/cmdline" | grep -q KnotClient; then
    echo "Refusing concurrent Minecraft PID=$pid" >&2; exit 1
  fi
done
# Same pinned toolchain and native libraries as the real client, never print it.
while IFS= read -r -d '' kv; do export "$kv"; done < /home/jan/.cache/simpleclouds/launch.env
cd "$test_project"
while IFS= read -r kv; do
  case "$kv" in
    DISPLAY=*|XAUTHORITY=*|WAYLAND_DISPLAY=*|XDG_RUNTIME_DIR=*) export "$kv" ;;
  esac
done < <(systemctl --user show-environment)
unset DRI_PRIME
./gradlew --offline --no-daemon --max-workers=4 -q -I tools/test-classpath.gradle classes writeParityClasspath
timeout 45s java -Xmx256m --enable-native-access=ALL-UNNAMED \
  -cp "$(<build/parity-classpath.txt)" OriginalComputeContextTest.java

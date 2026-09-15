#!/usr/bin/env bash
set -euo pipefail
cd /home/jan/.cache/simpleclouds/port-candidate-tgkFnnmU
while IFS= read -r -d '' kv; do export "$kv"; done < /home/jan/.cache/simpleclouds/launch.env
MC=/home/jan/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar
CP="build/classes/java/main:build/resources/main:$MC"
while IFS= read -r -d '' jar; do CP="$CP:$jar"; done < <(find /home/jan/.gradle/caches/modules-2/files-2.1 -type f \( -name 'log4j-*.jar' -o -name 'joml-*.jar' -o -name 'fastutil-*.jar' -o -name 'slf4j-*.jar' -o -name 'guava-*.jar' -o -name 'gson-*.jar' \) -print0)
for test in CpuChunkSeamTest CpuEmptyChunkTest ChunkBufferPoolTest ChunkGenerationKeyTest ShaderFaceTest StormCoverageTest StormFogMapTest; do
  echo "RUN $test"
  java -cp "$CP" "$test.java"
done

#!/usr/bin/env bash
set -euo pipefail
cd "$(cd "$(dirname "$0")/.." && pwd)"
while IFS= read -r -d '' kv; do export "$kv"; done < /home/jan/.cache/simpleclouds/launch.env
MC_VERSION=$(sed -n 's/^minecraft_version=//p' gradle.properties | tr -d '\r')
MC="/home/jan/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/$MC_VERSION/minecraft-merged-deobf-$MC_VERSION.jar"
test -f "$MC" || { echo "Missing Minecraft $MC_VERSION test dependency: $MC" >&2; exit 1; }
./gradlew --no-daemon --max-workers=8 -q -I tools/test-classpath.gradle classes writeParityClasspath
CP="$(<build/parity-classpath.txt):$MC"
for test in CloudRefreshScheduleTest CloudMixedLodCoverageProbe StormFragmentCoverageTest CloudFaceCoverageTest CloudWorldCoverageTest CloudRelocationParityTest CloudGridTransitionTest KeyMappingMigrationTest ClientCoordinatesTest CloudArgumentWireTest CloudCommandTreeTest CloudSyncPositionTest EventBusTest PreviewCameraTest PreviewSelectionTest CloudTypeSelectionTest RainSoundTest CloudMotionProbe CloudScrollSmootherTest CloudFaceDeltaTest WeatherStateTest LocalWeatherClassificationTest LightningBatchTest PrecipitationParityTest TransparencyBoundaryTest PsrdNoiseTest CpuChunkSeamTest CpuEmptyChunkTest RegionNoiseCacheTest ChunkBufferPoolTest ChunkGenerationKeyTest ShaderFaceTest StormCoverageTest StormFogMapTest; do
  echo "RUN $test"
  java -cp "$CP" "$test.java"
done
for mode in unset disabled; do
  echo "RUN DevShotDisabledTest ($mode)"
  if [ "$mode" = unset ]; then
    env -u SIMPLECLOUDS_DEV java -cp "$CP" DevShotDisabledTest.java
  else
    SIMPLECLOUDS_DEV=0 java -cp "$CP" DevShotDisabledTest.java
  fi
done

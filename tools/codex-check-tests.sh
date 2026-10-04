#!/usr/bin/env bash
set -euo pipefail
cd "$(cd "$(dirname "$0")/.." && pwd)"
test_project=$(pwd -P)
while IFS= read -r -d '' kv; do export "$kv"; done < /home/jan/.cache/simpleclouds/launch.env
cd "$test_project"
MC_VERSION=$(sed -n 's/^minecraft_version=//p' gradle.properties | tr -d '\r')
MC="/home/jan/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/$MC_VERSION/minecraft-merged-deobf-$MC_VERSION.jar"
test -f "$MC" || { echo "Missing Minecraft $MC_VERSION test dependency: $MC" >&2; exit 1; }
./gradlew --no-daemon --max-workers=8 -q -I tools/test-classpath.gradle classes writeParityClasspath
CP="$(<build/parity-classpath.txt):$MC"
echo "RUN BoundedDeferredQueueTest"
java -cp "$CP" BoundedDeferredQueueTest.java
echo "RUN LocalWeatherEffectsTest"
echo "RUN ParticleCancellationCompatTest"
java -cp "$CP" ParticleCancellationCompatTest.java
echo "RUN NativeDustProvenanceTest"
java -cp "$CP" NativeDustProvenanceTest.java
env -u SIMPLECLOUDS_DEV -u SIMPLECLOUDS_TEST_NATIVE_BIOME_WEATHER java -cp "$CP" NativeWeatherMatrixDisabledTest.java
SIMPLECLOUDS_DEV=1 SIMPLECLOUDS_TEST_NATIVE_BIOME_WEATHER=0 java -cp "$CP" NativeWeatherMatrixDisabledTest.java
SIMPLECLOUDS_DEV=0 SIMPLECLOUDS_TEST_NATIVE_BIOME_WEATHER=1 java -cp "$CP" NativeWeatherMatrixDisabledTest.java
env -u SIMPLECLOUDS_DEV -u SIMPLECLOUDS_TEST_PARTICLE_SNOW java -cp "$CP" ParticleSnowProbeDisabledTest.java
SIMPLECLOUDS_DEV=1 SIMPLECLOUDS_TEST_PARTICLE_SNOW=0 java -cp "$CP" ParticleSnowProbeDisabledTest.java
SIMPLECLOUDS_DEV=0 SIMPLECLOUDS_TEST_PARTICLE_SNOW=1 java -cp "$CP" ParticleSnowProbeDisabledTest.java
for developer in 0 1; do
  for dedicated in 0 1; do
    for weather in 0 1; do
      [[ "$developer$dedicated$weather" == 111 ]] && continue
      SIMPLECLOUDS_DEV="$developer" SIMPLECLOUDS_TEST_DEDICATED="$dedicated" SIMPLECLOUDS_TEST_WEATHER_CONNECTION="$weather" java -cp "$CP" WeatherConnectionDisabledTest.java
    done
  done
done
java -cp "$CP" LocalWeatherEffectsTest.java
echo "RUN RecentWetColumnsTest"
java -cp "$CP" RecentWetColumnsTest.java
echo "RUN OriginalWorldArchitectureTest"
echo "RUN PacketRegistrationBoundaryTest"
java PacketRegistrationBoundaryTest.java
echo "RUN ServerConfigSyncTest"
java -cp "$CP" ServerConfigSyncTest.java
echo "RUN CloudTypeConnectionLifetimeTest"
java -cp "$CP" CloudTypeConnectionLifetimeTest.java
echo "RUN CloudTypeResourcePathTest"
java -cp "$CP" CloudTypeResourcePathTest.java
java OriginalWorldArchitectureTest.java
echo "RUN OriginalTransparencyContractTest"
java OriginalTransparencyContractTest.java
echo "RUN OriginalPreviewTransformTest"
java -cp "$CP" OriginalPreviewTransformTest.java
echo "RUN CloudEditorModelTest"
java -cp "$CP" CloudEditorModelTest.java
echo "RUN CloudEditorFilesTest"
java -cp "$CP" CloudEditorFilesTest.java
echo "RUN CloudPreviewReadbackTest"
java -cp "$CP" CloudPreviewReadbackTest.java
echo "RUN AtmosphericNoiseContractTest"
java -cp "$CP" AtmosphericNoiseContractTest.java
echo "RUN OriginalAtmosphericUniformsTest"
java -cp "$CP" OriginalAtmosphericUniformsTest.java
echo "RUN AtmosphericOrderingContractTest"
java -cp "$CP" AtmosphericOrderingContractTest.java
echo "RUN OriginalTerrainFogTest"
java -cp "$CP" OriginalTerrainFogTest.java
echo "RUN OriginalWorldFogTest"
java -cp "$CP" OriginalWorldFogTest.java
echo "RUN DeferredWeatherContractTest"
java DeferredWeatherContractTest.java
echo "RUN NativeSnowFixtureTest"
java NativeSnowFixtureTest.java
echo "RUN ServerConfigCommandsTest"
java -cp "$CP" ServerConfigCommandsTest.java
echo "RUN WorldConfigBindingTest"
java -cp "$CP" WorldConfigBindingTest.java
echo "RUN NestedWorldConfigReloadTest"
java -cp "$CP" NestedWorldConfigReloadTest.java
echo "RUN OriginalStormShadowTransformTest"
java -cp "$CP" OriginalStormShadowTransformTest.java
echo "RUN OriginalStormFogUniformsTest"
java -cp "$CP" OriginalStormFogUniformsTest.java
echo "RUN FrustumCullParityTest"
java -cp "$CP" FrustumCullParityTest.java
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
echo "RUN DedicatedProbeDisabledTest (production and developer-only)"
echo "RUN CounterReloadDisabledTest (all partial opt-in combinations)"
echo "RUN DhFogLifecycleDisabledTest (unset and partial opt-in)"
echo "RUN NativeBiomeWeatherDisabledTest (unset and partial opt-in)"
env -u SIMPLECLOUDS_DEV -u SIMPLECLOUDS_TEST_NATIVE_BIOME_WEATHER java -cp "$CP" NativeBiomeWeatherDisabledTest.java
SIMPLECLOUDS_DEV=0 SIMPLECLOUDS_TEST_NATIVE_BIOME_WEATHER=1 java -cp "$CP" NativeBiomeWeatherDisabledTest.java
SIMPLECLOUDS_DEV=1 SIMPLECLOUDS_TEST_NATIVE_BIOME_WEATHER=0 java -cp "$CP" NativeBiomeWeatherDisabledTest.java
env -u SIMPLECLOUDS_DEV -u SIMPLECLOUDS_TEST_DH_FOG_LIFECYCLE java -cp "$CP" DhFogLifecycleDisabledTest.java
SIMPLECLOUDS_DEV=0 SIMPLECLOUDS_TEST_DH_FOG_LIFECYCLE=1 java -cp "$CP" DhFogLifecycleDisabledTest.java
SIMPLECLOUDS_DEV=1 SIMPLECLOUDS_TEST_DH_FOG_LIFECYCLE=0 java -cp "$CP" DhFogLifecycleDisabledTest.java
for developer in 0 1; do
  for snapshot in 0 1; do
    for reload in 0 1; do
      if [ "$developer$snapshot$reload" = 111 ]; then continue; fi
      SIMPLECLOUDS_DEV="$developer" SIMPLECLOUDS_TEST_COUNTER_SNAPSHOT="$snapshot" SIMPLECLOUDS_TEST_COUNTER_RELOAD="$reload" java -cp "$CP" CounterReloadDisabledTest.java
    done
  done
done
env -u SIMPLECLOUDS_DEV -u SIMPLECLOUDS_TEST_COUNTER_SNAPSHOT -u SIMPLECLOUDS_TEST_COUNTER_RELOAD java -cp "$CP" CounterReloadDisabledTest.java
env -u SIMPLECLOUDS_DEV -u SIMPLECLOUDS_TEST_DEDICATED -u SIMPLECLOUDS_TEST_SLEEP java -cp "$CP" DedicatedProbeDisabledTest.java
env -u SIMPLECLOUDS_TEST_DEDICATED -u SIMPLECLOUDS_TEST_SLEEP SIMPLECLOUDS_DEV=1 java -cp "$CP" DedicatedProbeDisabledTest.java
echo 'RUN WeatherConnectionDisabledTest (all inactive combinations)'
for developer in 0 1; do
  for dedicated in 0 1; do
    for connection in 0 1; do
      for fullpack in 0 1; do
        if [[ "$developer" == 1 && ( "$fullpack" == 1 || "$dedicated$connection" == 11 ) ]]; then continue; fi
        SIMPLECLOUDS_DEV="$developer" SIMPLECLOUDS_TEST_DEDICATED="$dedicated" SIMPLECLOUDS_TEST_WEATHER_CONNECTION="$connection" SIMPLECLOUDS_TEST_FULLPACK_WEATHER_LIFECYCLE="$fullpack" java -cp "$CP" WeatherConnectionDisabledTest.java
      done
    done
  done
done
echo 'RUN FullPackWeatherLifecycleDisabledTest (all inactive combinations)'
env -u SIMPLECLOUDS_DEV -u SIMPLECLOUDS_TEST_FULLPACK_WEATHER_LIFECYCLE java -cp "$CP" FullPackWeatherLifecycleDisabledTest.java
SIMPLECLOUDS_DEV=0 SIMPLECLOUDS_TEST_FULLPACK_WEATHER_LIFECYCLE=1 java -cp "$CP" FullPackWeatherLifecycleDisabledTest.java
SIMPLECLOUDS_DEV=1 SIMPLECLOUDS_TEST_FULLPACK_WEATHER_LIFECYCLE=0 java -cp "$CP" FullPackWeatherLifecycleDisabledTest.java
echo 'RUN IndexedCloudBlendDisabledTest (production and partial opt-ins)'
echo 'RUN IndexedCloudBlendSelectionTest (pure capability/override matrix)'
java -cp "$CP" IndexedCloudBlendSelectionTest.java
env -u SIMPLECLOUDS_DEV -u SIMPLECLOUDS_TEST_OIT_MRT SIMPLECLOUDS_TEST_OIT_MRT_PARITY=1 java -cp "$CP" IndexedCloudBlendDisabledTest.java
SIMPLECLOUDS_DEV=0 SIMPLECLOUDS_TEST_OIT_MRT=1 SIMPLECLOUDS_TEST_OIT_MRT_PARITY=1 java -cp "$CP" IndexedCloudBlendDisabledTest.java
SIMPLECLOUDS_DEV=1 SIMPLECLOUDS_TEST_OIT_MRT=0 SIMPLECLOUDS_TEST_OIT_MRT_PARITY=1 java -cp "$CP" IndexedCloudBlendDisabledTest.java

echo 'RUN NativeRainRoofDisabledTest (production and partial opt-ins)'
env -u SIMPLECLOUDS_DEV -u SIMPLECLOUDS_TEST_NATIVE_RAIN_ROOF java -cp "$CP" NativeRainRoofDisabledTest.java
SIMPLECLOUDS_DEV=0 SIMPLECLOUDS_TEST_NATIVE_RAIN_ROOF=1 java -cp "$CP" NativeRainRoofDisabledTest.java
SIMPLECLOUDS_DEV=1 SIMPLECLOUDS_TEST_NATIVE_RAIN_ROOF=0 java -cp "$CP" NativeRainRoofDisabledTest.java

# Rebuild and replace the integrated weather modules

This private handoff supplies source/build material, not a claim of legal
clearance for public distribution. Keep each component's license and notices.

## Base port

Use Java 25 and the committed Gradle wrapper. From the extracted port-source
directory, run `bash tools/build-weather-libraries.sh` then
`./gradlew --no-daemon -PintegratedWeather=true build`.
The first dependency resolution requires network access; `--offline` works only
after the declared dependencies are cached. The DH API compile-only dependency
requires a 7.1.0 API JAR; on another machine pass
`-PdhApiJar=/absolute/path/DistantHorizonsApi-7.1.0.jar` to Gradle.
The Mony artifact SHA256 is
b02f5605f4ea641fa669232fb563a8a6497a09822ee604f14c9b1a86d6177b6c,
from https://gitlab.com/distant-horizons-team/distant-horizons at revision
d0efcc17e81d8ee6e86c6b64030d51d1d0022f99. It is a compile-only prerequisite,
not Minecraft code. Source/build portability for this dependency still needs a
clean-environment check before the final release is called reproducible.
Do not interpret a Java sources JAR as the whole build project.

## Supplied upstream sources

The bundle's upstream-source directory contains these exact, unmodified archives:

- Particle Rain ff0e693a11a38fad454be72527ef605484e61c69, SHA256
  68b0b8d318d42c34b934c8500dec5ddc4536c29bb70d6f5a91ca11f9e8309f97.
- Immersive Storms 157767f062f464678f96af3c104def5c1f721ac3, SHA256
  706ac420a2dfec3c535d988303d8c6df7eed91e502bd1f1b8294b71aded08107.

Particle Rain: https://github.com/PigCart/particle-rain . From its extracted root,
run `./gradlew --configure-on-demand --no-daemon :26.3-fabric:build`.
Output: versions/26.3-fabric/build/libs/particlerain-4.0.0-beta.100+26.3-fabric.jar.

Immersive Storms: https://github.com/TheDeathlyCow/immersive-storms . From its
extracted root, run `./gradlew --no-daemon build`.
Output: build/libs/immersive-storms-1.8.0+26.3.jar.

## Modified-module rebuild / relinking

1. Extract the supplied module sources into your own new directory, preserve
   notices and make your modifications there. Build with the upstream wrapper.
2. Copy the resulting module JAR to the base port's build/weather-libraries as
   particle-rain.jar or immersive-storms.jar respectively. Keep the matching
   upstream license texts there. Preserve compatible mod IDs/API/version metadata.
3. Do not rerun build-weather-libraries.sh at this point: it deliberately builds
   the pinned, unmodified source and would replace your module output.
4. Run `./gradlew --no-daemon --rerun-tasks -PintegratedWeather=true build`.
   The artifact-only local Ivy repository packages your rebuilt separate module
   JAR. The source does not require a private signing key or forbid replacement.
5. Install only with Minecraft closed and preserve the previous artifact/config.
   Test compatibility; replacing a module is not a guarantee unchanged adapters
   remain compatible with arbitrary API changes.

This is a build-time replacement path. Do not assume Fabric chooses an arbitrary
external same-ID JAR over a nested module without testing its resolver/version
constraints. Base adapters remain in the supplied port sources and can also be
changed/rebuilt. No conversation data, credentials or user saves belong in the
source bundle.

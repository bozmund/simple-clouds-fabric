# Mony FPS improvement — installed 2026-10-01

## Request and delivered scope

Jan requested FPS improvement work. Preserve Intel rendering, HIGH quality, shadows, transparency, render/simulation distances, V-Sync, worlds, other mods and GPU-only original generation. No Git mutation performed.

## Changes

1. Cache compute-program uniform locations (including absent uniforms) for the lifetime of each linked program. Cache compute work-group count limits once per instance. Reload constructs fresh instances; close clears the location cache. No GL binding restoration contract changed.
2. Replace the original intermediate, unused counter mappings in `CloudMeshGenerator.onOffGen()` with a shader-storage GPU memory barrier. Keep actual counter read/reset at final mesh publication. Add buffer-update visibility at publication before mapping/copying shader output, consistent with [Khronos memory barrier documentation](https://wikis.khronos.org/opengl/GLAPI/glMemoryBarrier).
3. Select existing `generationInterval = "STATIC"`, `framesToGenerateMesh = 5` in Jan's client config. Only the interval selector changed. This distributes mesh calculation through five rendered frames. Geometry quality stays the same, but shape refresh frequency is lower than a one-frame cycle; continuous cloud movement still uses original rendering. Jan should check perceived cube-update smoothness while playing.
4. Extend opt-in `SIMPLECLOUDS_PROFILE` logging with inter-frame mean, derived FPS, p95 and maximum times. Profiling remains disabled during ordinary gameplay. No diagnostic cadence or skip-map environment override remains in the production source/launcher.

## Validation and limits

- Baseline `...RAINREPLAY-09I5aT`: storm-fixture generation approximately 46 ms; observed replay FPS approximately 18, interval one frame. Earlier `NawES6` was NOT a valid five-frame test because its launcher lacked the environment flag.
- Valid five-frame baseline `...RAINREPLAY-15zVor`: 81 frames, runtime gate passed, explicit `intervalFrames=5`, observed FPS 60. This isolates the scheduling improvement from the cache changes.
- Cache + five frames `...RAINREPLAY-ci2w4Y`: 81 frames, passed; steady approximately 59 FPS, p95 approximately 19.24–19.36 ms. No separately measured cache-only FPS gain is claimed.
- Skip unused maps experiment `...RAINREPLAY-FKV6Gd`: 81 frames, passed; approximately 60 FPS, p95 approximately 18.19–18.47 ms. CPU generation dropped from approximately 8–11 ms to approximately 0.5 ms.
- Final production code and actual STATIC config, no test cadence/skip flags: `...RAINREPLAY-tntUCA`, 81 frames and runtime gate passed. Steady frame mean approximately 16.67 ms (60 FPS), p95 approximately 18.47–18.48 ms; generation CPU approximately 0.56–0.65 ms. Startup windows include substantially higher frame times and are not hidden. Frame 41 reviewed without colored rain fragments; this does NOT resolve the separate intermittent rain bug.
- `tools/codex-check-tests.sh` exited 0, including production DevShot and dedicated fixture inertness tests. Full log `/tmp/simpleclouds-performance-helper-tests-20261001.log`, SHA-256 `8a1c0fa5f28200ebc4b42265415580aca7f694287f0aeb328a0fb0753f9cafbc`.
- These are controlled storm-fixture results, not guaranteed FPS in Jan's world. V-Sync remains enabled; no uncapped throughput claim. A matched full-pack above/below-water phase benchmark has not yet been completed.

## Installation and recovery

Installed normally in `/home/jan/.local/share/ModrinthApp/profiles/Fabric 26.3/mods/simple-clouds-0.7.3+26.3-fabric.jar`.

New JAR SHA-256: `b1676cd704db0d50aca61eb744303ac666172487df39c01e00ada5f6a41ac396`.

New config SHA-256: `261858778287fb0d2468ffd29063d2b734889f47a35514e6bf41bc59a0e954be`.

Backup: `/home/jan/.cache/simpleclouds/performance-install-20261001-GMpCIR`, containing `previous.jar`, `previous-client.toml`, and copies of installed files. Previous JAR SHA-256: `f1b4119a82216773eb43e81cce6d40e8a9dd88c13fe07bc06b2852afaea71ce2`.

Installation guarded against running Minecraft and unexpected previous JAR/config content, atomic JAR replacement, config difference restricted to interval selector, automatic restoration on installation failure. No worlds removed or edited except the existing isolated test world. Test clients stopped and no game automatically launched for Jan.

Next: user gameplay FPS and smoothness feedback. If further performance work is needed, measure fog above/below water and uncapped frame budgets; do not attribute a V-Sync ceiling or gameplay CPU load to clouds without measurements.

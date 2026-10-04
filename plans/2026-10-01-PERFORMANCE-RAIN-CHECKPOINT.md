# Performance and intermittent rain checkpoint — 2026-10-01

## Active request and constraints

Jan reports approximately 40–50 FPS above water and over 60 underwater. He closed Minecraft for controlled tests. During testing he reported colored rain fragments, then confirmed that they disappeared in the subsequent run. Rain correctness takes priority over optimization. Keep Intel rendering, existing quality, worlds and other mods intact. No Git mutations authorized.

## Observed evidence

- Full-pack `RAINREPLAY` baseline: `evidence-codex-20260930-fullpack-coverage-RAINREPLAY-09I5aT`, 81 frames, runtime gate passed. Steady generation GPU approximately 46.2–46.6 ms, CPU approximately 45.9–47.3 ms; opaque approximately 1.14 ms, transparency approximately 0.10 ms. Post-world fog is NOT included in the four existing stage timings. These storm-fixture values are not Jan's gameplay FPS measurements.
- SSBO binding 77 mapping averages approximately 43.8–45.5 ms. This is verified synchronous CPU waiting on GPU-produced data; necessity/correctness of that read has not yet been changed.
- `...-NawES6`: 81 frames, runtime gate passed, but colored fragments clearly present in frame 41. A requested diagnostic five-frame interval did NOT reach the process: the actual harness launches repository-root `real-launch.sh`, not `tools/real-launch.sh`. Log reports `intervalFrames=1`. Therefore this run is NOT a valid five-frame cadence comparison. Remove unused diagnostic override instead of interpreting it as a negative result.
- `...-iRWrLP`: same rain fixture, diagnostic `SIMPLECLOUDS_TEST_ASYNC_GL_RESTORE=1` verified in actual process environment; 81 frames and runtime gate passed. Frame 41 visually clean; Jan also reported no fragments. No `[ASYNC-GL-RESTORE]` binding-change messages were emitted. This does not prove the restoration code caused the clean result. Earlier ON/OFF pairs were also intermittently clean. Do not enable this candidate in production yet.
- Paired OFF repeat `...-XW6ZVh` failed before capture. Full log contains a Voice Chat microphone-thread chat/font upload exception: `Close the existing render pass before performing additional commands`. It is not a valid rain comparison and must not be counted as successful or evidence of a rain fix. Preserve the log; do not remove unrelated mods from the user's profile.

## State left intact

- Installed user-profile JAR remains SHA-256 `f1b4119a82216773eb43e81cce6d40e8a9dd88c13fe07bc06b2852afaea71ce2` after all harness restores.
- Test clients stopped. No background build/test left running.
- No production graphics policy, configuration, model, or other mod changed. Unused cadence test override removed from source and tools launcher.
- The build artifact may still be the diagnostic candidate; do not install it without rebuilding from current source and validating.

## Next discriminating checks

1. Repeat matched rain OFF/ON with valid runtime gates; require actual append execution and inspect all 81 images, not just one clean frame. GL binding queries may alter timing, so clean output alone is insufficient proof.
2. Inspect actual draw-time VAO/VBO layout and published append buffer state without synchronizing readback. Preserve AsyncParticles atomic accounting. Isolate state corruption versus timing/publication races.
3. Once rain is reliable, measure above/below water with phase-labeled frame times including post-world fog, then revisit mesh scheduling. Verify test environment on the actual launched PID. Do not sacrifice original smooth movement or lower quality as an unmeasured workaround.

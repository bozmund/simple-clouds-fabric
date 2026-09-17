# Claude handoff — Simple Clouds cloud-motion fix

**Date:** 2026-09-15 CEST  
**Owner request:** make the Fabric 26.2 port ready for normal playing; clouds currently appear to shift/pop too much.

## Repository and safety

- Work in `/home/jan/simple-clouds-fabric` only. This is the authoritative checkout.
- Current Git commit before this handoff: `f6ad62b` (`Notes: atmospheric fix evidence, view-A gate check, install of 8d3d1130`).
- The old `/home/jan/.cache/simpleclouds/port-candidate-*` copy is frozen reference material; do not copy it over this checkout.
- Preserve `/home/jan/.local/share/ModrinthApp/profiles/Fabric 26.2/local-mods/simple-clouds` and all existing user data.
- Do not reset, discard, commit, push, or install a JAR without Jan's explicit authorization for that exact operation.
- Never stop Jan's real game or unrelated AI/Symbiote processes. The isolated dev unit is `simpleclouds-devclient`.
- Do not use `dev-relaunch.sh --install`; its historical relative destination is unsafe. Backup the installed JAR before any explicitly authorized install.

## Installed and current state

- Real profile JAR: `8d3d11306c62149828f6ac547080cb80564b48e00513585da745c09ae19266ab`.
- It includes the verified atmospheric-layer depth fix (cirrus no longer paints over terrain) and is byte-identical to the last build in this checkout.
- Previous installed JAR `1fa5ff98...` is backed up under `/home/jan/.cache/simpleclouds/claude-backups/installed-1fa5ff98-20260915-122126`.
- No `FAST` setting or active real-profile DevShot request was found. Saved cloud speed is `1.0` in the checked worlds. Do not reset settings or saves.
- The checkout currently has uncommitted work from the handoff preparation:
  - `src/main/java/dev/nonamecrackers2/simpleclouds/client/DevShot.java`: developer automation now requires `SIMPLECLOUDS_DEV=1`, preventing a stale request from affecting ordinary play.
  - `DevShotDisabledTest.java`: test for that production guard.
  - `PsrdNoiseTest.java`: an independent parity-test draft; it has not yet been accepted as passing.
- The production `PsrdNoise.java` implementation is not yet changed by this handoff.

## Verified work already complete

- Per-frame Dynamic Transforms reset and bounded-cap diagnostics.
- Terrain capture gate with integrated-server teleport synchronization and monotonic waiting.
- Spatial storm-fog map, depth-aware fog, local lightning contribution, and sky-only lightning flash.
- Atmospheric layer is depth-masked to sky pixels; this was built and installed as `8d3d1130`.
- Seven standalone CPU/shader/storm tests pass in the prior evidence.
- A 30-minute isolated LOOP stress run passed: 1805 seconds, 30 cycles, zero failed chunks, no Simple Clouds errors or OOM; RSS was approximately 3.28→3.44 GB and direct buffers 68→72 MB. Caveats: Minecraft's AFK frame cap affected about 13 minutes and two approximately 2.2-second hitches remain unexplained.

## Current symptom and evidence

- In normal play, Jan reports that clouds shift too much. This is not currently explained by a test mode or a speed multiplier.
- The CPU renderer generates a complete replacement batch when the scroll phase crosses `SCROLL_REGEN_THRESHOLD` (currently 1 cloud unit) and cross-fades old/new batches for `TRANSITION_NANOS` (currently 300 ms).
- Claude's evidence showed large field changes in one or two frames and `SHAKE FAST` frames with semi-transparent double-exposed clouds. A longer fade alone would hide the symptom rather than fix the discontinuity.
- Drift sign and lattice anchoring are internally consistent: generated position plus the draw-time offset preserves translational noise. Do not change the sign or user-facing cloud speed without a discriminating test.
- The intended topology-changing `Wiggle` term is derived from scroll phase (`(scrollX + scrollY + scrollZ) / 5` in the original shader). A phase change can legitimately alter the sampled shape, but the port must not replace a large fraction of the field abruptly.

## Next work (in this order)

1. **Reproduce and classify the motion in the authoritative checkout.** Separate continuous translation, intentional noise/topology evolution, batch replacement, and camera/render artifacts. Capture normal-speed and accelerated diagnostic runs; do not alter the real profile yet.
2. **Verify the CPU noise port against the bundled GLSL source** (`assets/simpleclouds/shaders/include/psrdnoise.glsl`). Run `PsrdNoiseTest.java` only after checking its reference equations and tolerances. There is a suspected rank/swizzle mismatch and a suspected Java `%` versus GLSL `mod` difference for negative coordinates; neither is a confirmed gameplay cause yet. Correct production noise only if a source-level parity test fails.
3. **Fix the actual discontinuity with the smallest bounded change.** Prefer preserving the mathematical phase and updating/replacing chunks progressively or from a phase-consistent snapshot. Keep memory bounded and never silently drop failed chunks. Do not simply lengthen the cross-fade or clamp motion until evidence shows that is correct.
4. **Build and run the required evidence:** normal SHAKE, SHAKE FAST, A/B/C/D/F, STORM, NOFOG/HIDEFLASH as relevant; inspect full-resolution screenshots and logs. Treat build success as insufficient for visual claims.
5. **Repeat the 30-minute stress test after the motion fix**, with the AFK cap disabled in the isolated dev options and with heap/direct/RSS, worst frame, transform capacity, batch transitions, and failed chunks recorded. Investigate the two earlier hitches with GC/frame evidence if they recur.
6. **Only after evidence passes and Jan explicitly requests it:** build the final JAR, record its checksum, back up the installed JAR, install it in the real profile, and ask Jan to test ordinary play. Update this handoff with observed/verified results.

## Reporting requirements

For every attempt record: changed files, exact command, build result, runtime result, screenshots/logs inspected, memory and batch/transform peaks, symptom observed, confidence (`observed`, `suspected`, or `verified`), and the next discriminating check. Reassess assumptions after two similar failures.

## Useful paths

- Progress: `/home/jan/simple-clouds-fabric/PORT-PROGRESS-2026-09-14.md`
- Previous handoff: `/home/jan/simple-clouds-fabric/plans-claude-handoff.md`
- Dev launcher: `/home/jan/simple-clouds-fabric/dev-relaunch.sh`
- Dev runner: `/home/jan/simple-clouds-fabric/tools/claude-run.sh`
- Real mods: `/home/jan/.local/share/ModrinthApp/profiles/Fabric 26.2/mods`

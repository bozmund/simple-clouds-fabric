> **11:50 update:** 30-minute LOOP stress PASSED (30 cycles, failed=0 everywhere, no errors, RSS 3.28 -> 3.44 GB,
> direct 68 -> 72 MB, transform capacity <= 32). Caveats: AFK 10-FPS cap for 13 min, two ~2.2 s hitches. Details: end of
> `PORT-PROGRESS-2026-09-14.md`. Open bug from real play: atmospheric (cirrus) layer drawn over blocks - unfixed.
> **Repository (2026-09-15 11:35):** all further work happens in `/home/jan/simple-clouds-fabric`
> (GitHub `bozmund/simple-clouds-fabric`, private). Branch per task, one commit per verified step,
> push when done, never force-push or rewrite `main`. The old candidate folder is frozen (read-only
> reference, evidence lives there); `local-mods/simple-clouds` stays untouched. Evidence folders are
> gitignored: keep them in the checkout, cite them in the notes. Do not use `dev-relaunch.sh --install`.

> **11:15 update:** fog "horizon streaks" NOT reproduced (fog-on band texture = fog-off control, only 2-5 px dither);
> earlier description was a thumbnail misreading. No code change. Item closed. Details: end of `PORT-PROGRESS-2026-09-14.md`.
> **10:50 update:** S4 moved to an oblique view (2000 blocks east, Y 2600, 30 deg down; jar d91c38c0, not installed).
> S4 now 44.6 % white instead of 100 %, and shows the storm top as one flat unshaded white area - reproducible test for the
> tops-shading question. Details: end of `PORT-PROGRESS-2026-09-14.md`.
> **10:30 update:** STORM fixture now repeatable (DevShot: full-size storm, fixed scroll angle; jar 35ab0b14, not installed).
> Run-to-run diff 16.8 -> 5.5; fog A/B pair 21-storm-b / 22-storm-nofog is scene-identical. S4 is now always 100 % white (view placement).
> Details: end of `PORT-PROGRESS-2026-09-14.md`.
> **10:15 update:** step 1 re-runs done (5 runs, all frames, no errors). Hide Lightning Flashes verified; no strike
> brightens terrain; depth convention consistent. Grey S3 and upper-sky streaks are NOT fog (corrects 09:55).
> New: STORM scenes differ run to run (storm growth), batch swaps pop (300 ms cross-fade). Details: end of `PORT-PROGRESS-2026-09-14.md`.
> **09:55 update:** Codex continued 09:15-09:38 (`2026-09-15-codex-progress.md`); Claude review and corrections
> (wrong S4 baseline, half-built NOFOG S3/S4, new upper-sky fog banding) at the end of `PORT-PROGRESS-2026-09-14.md`.

# Simple Clouds Fabric 26.2 — handoff (updated 2026-09-15 01:30 CEST by Claude)

READ THIS SECTION FIRST. It supersedes "Current verified state" and "Remaining work" of the
00:40 handoff further below (kept for history). Evidence details: `PORT-PROGRESS-2026-09-14.md`,
section "2026-09-15 00:45–01:30 CEST (Claude)".

## Rules (unchanged)

- Work only in `/home/jan/.cache/simpleclouds/port-candidate-tgkFnnmU` (NOT a git repo — a copy).
- Do not reset/discard/commit/push; preserve the original dirty repo and
  `/home/jan/.cache/simpleclouds/port-preserved-o2UtLgh1` (storm-fog experiment).
- Do not install into the real Modrinth profile until visual + stress tests pass AND Jan asks.
  The current candidate jar (sha256 `ca85968f915a5ece5e4bc36804ecbeb880ab659bc664c8d3f8c503b193b48ec4`)
  is NOT validated. `dev-relaunch.sh --install` copies to `../../mods` = `~/.cache/mods` from this
  folder — do not use it.
- Never kill Jan's game or other AI/Symbiote processes; stop only `simpleclouds-devclient`.
- Never claim success from compilation; label results observed / suspected / verified.
- Two failed attempts for the same reason → stop and reassess the assumption.
  **The DevShot terrain gate has used its two attempts** (see step 1).

## How to test (tools added this session, in `tools/`)

- `tools/claude-run.sh <evidence-dir> <DEVSHOT tokens...>` — refuses while the real game runs,
  runs `./dev-relaunch.sh`, copies log + screenshots into `<candidate>/<evidence-dir>/`, builds
  `contact-sheet.png`, prints generation summaries, `[DEVMEM]`, transform capacity, gate lines,
  Simple Clouds errors, then stops only `simpleclouds-devclient`. Also at `~/.cache/simpleclouds/claude-run.sh`.
  Examples: `tools/claude-run.sh evidence-x-storm STORM`, `... STORM NOFOG`, `... STORM FOGDEBUG`,
  `... STORM NOFLASH`, `... SHAKE`, `... SHAKE FAST`, `... A B C D F`, `... A B C D F LOOP` (stress).
- `tools/sheet.py '<glob>' out.png`, `tools/imgstat.py <png>...` (mean/std/pure-white share).
  Pillow: `nix shell --impure --expr '(builtins.getFlake "path:/home/jan/nixos-symbiote").inputs.nixpkgs.legacyPackages.x86_64-linux.python3.withPackages (ps: [ ps.pillow ])'`.
- Standalone tests (all 7 pass as of 01:10): load `~/.cache/simpleclouds/launch.env`, then
  `java -cp "build/classes/java/main:build/resources/main:$MC:$LIBS" <Test>.java` with
  `MC=~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar`
  and `LIBS` = the log4j/joml/fastutil/slf4j/guava/gson jars from `~/.gradle/caches/modules-2/files-2.1`.
  Tests: CpuChunkSeamTest, CpuEmptyChunkTest, ChunkBufferPoolTest, ChunkGenerationKeyTest,
  ShaderFaceTest, StormCoverageTest, StormFogMapTest (new). StormCoverage/StormFogMap need only
  `build/classes/java/main`.
- Originals of every file Claude changed: `~/.cache/simpleclouds/claude-backups/20260915-004924-before-transform-ring/`
  (CloudsDrawPipeline, SimpleCloudsRenderer, DevShot, CpuCloudGenerator, storm_fog.fsh, sky_flash.fsh, ShaderFaceTest).

## State at 01:30 (what changed and what is proven)

1. **Dynamic Transforms bound — verified (SHAKE, STORM, 8-min STORM NOFOG).** Cause (verified by
   bytecode): the private `DynamicUniforms ownTransforms` was never `reset()`, so writes accumulated
   across frames and replaced buffers were kept (`endFrame()` is what rewinds, rotates the 3 fenced
   ring buffers and frees them). Fix: `CloudsDrawPipeline.beginFrame()` (called once per frame from
   `SimpleCloudsRenderer.renderBeforeLevel`), all writes through `frameTransform()` with a
   16384-writes-per-frame cap that logs "Simple Clouds ERROR" and skips the draw; 30 s summary field
   `transformWritesPeakPerFrame`. Max "Dynamic Transforms UBO" capacity: 32 (SHAKE), 64 (STORM) vs
   65536 before. Peak writes/frame 1048–1216; consecutive equal matrices share a slot, hence the
   small capacity. No cap error in any run.
2. **STORM S2 placement — verified; terrain wait — partly.** S2 resolves the ground at its own
   column (Y 69 → camera Y 70; was the probe origin's Y 181); its upward view has no plants.
   Terrain gate (DevShot `terrainGate`, 90 s timeout → view refused + error):
   attempt 1 (`hasRenderedAllSections` + compile queue + 7x7 chunks) let S5-01..09 through with no
   terrain; attempt 2 (+ ground probes ahead at 96/160/224 blocks, ±25°, 2/3 compiled+visible for
   |pitch| ≤ 45, else non-empty visible sections) gives terrain in all 60 S5 frames but refuses S3
   (y 260, pitch −20, ground not in view) and S4 (y 2300, pitch 90, no visible sections).
3. **Spatial storm fog + local lightning — implemented, NOT accepted.** New `v2/StormFogMap`
   (32x32 cells of 64 blocks around the camera, rasterized from each chunk's storm columns,
   following each chunk's drift); `CpuCloudGenerator` keeps the last chunk's storm columns;
   `core/storm_fog.fsh` ray-marches 24 steps through that map below the cloud base, depth-aware
   (colour-only pass sampling the depth), up to 8 nearby bolts light the fog within 300 blocks;
   `core/sky_flash.fsh` is sky-only; bolt light off with `stormFogLightningFlashes` off, Hide
   Lightning Flashes, or DevShot `NOFLASH`; DevShot `FOGDEBUG` = distance view.
   Verified by log (STORM 01:07): 223-block strike → local fog light; 1503/3001/8000 → none; far
   strikes give distant thunder with delay (8000 blocks: 4 s, pitch 0.5).
   Build `ca85968f…` also changes the sky test to the cleared depth only (`d <= 1e-7`) because the
   reversed depth (near 1, far 0, ~near/distance) puts geometry beyond ~500 blocks below 1e-4 —
   **suspected, uploaded, not yet run.**
4. **Standalone tests: 7/7 pass.** `ShaderFaceTest` needed a parser fix (verified defect: it used
   `matches()` on lines like `if (side == 0) return vec3(...)`, so it never counted sides 0–4 and
   could not pass; clouds.vsh unchanged since 13:49). Its final message prints `[I@…` (cosmetic).

## Remaining work, in order

### 1. Terrain gate — REASSESS (two attempts used)
Wrong assumption so far: "ground ahead is in view" / "visible sections exist". Proposal to evaluate
before coding: project candidate ground points (terrain top on a grid inside the render distance)
with the camera's view-projection and keep only those inside the frustum; require ≥ 2/3 of them
`isSectionCompiledAndVisible`; if NO ground point is in the frustum (S3 looking up, S4 far above),
fall back to chunks loaded + `hasRenderedAllSections` + empty compile queue + a fixed settle after
the teleport. Time the wait with frames or `System.nanoTime`, not game time: after the S4 teleport
the game clock went BACK (log "after −23 ticks", server "Can't keep up! 69 ticks behind").
Pass: STORM gives 64/64 shots, no view refused, S5-01 shows terrain, S3/S4 captured.

### 2. S4 pure white — unresolved
STORM 01:07 S4 = mean 255, std 0 (100 % white); every older S4 (docs/storm-evidence) ≈ mean 150–168.
NOFOG run had no S4 (refused, step 1). At the S4 shot a cloud transition was running
(`transition=0.005`), no strike near it, camera 2300 blocks up. After step 1: STORM, STORM NOFOG,
STORM OVL0, STORM FOGDEBUG; compare S4 with `tools/imgstat.py`. Suspects (unproven): far geometry
classified as sky (threshold, fixed in `ca85968f`), the transition composite, cloud tops filling
the frame at brightness 1.

### 3. Depth convention on a captured frame (handoff requirement)
Run `STORM FOGDEBUG` (sky blue, geometry grey = distance/3000). Confirm the sky test (`d <= 1e-7`)
and that far clouds/terrain are grey, not blue. `terrain_shadows.fsh` still uses `d <= 0.0001`
(pre-existing): check whether it skips shadows on geometry beyond ~500 blocks.

### 4. Whole-frame brightening on strikes
Observed: STORM (fog on, old threshold) S5-18/19 clouds + horizon brighter; STORM NOFOG S5-07 and
S5-22..24 whole frame incl. terrain brighter — so NOT the fog pass. S5 frame k ≈ 0.25 s·(k−1) after
S5-01; forced strikes at ≈ +1 s (200 blocks), +5 s (1500), +9 s (3000), +13 s (8000) → frames ≈ 5,
21, 37, 53; the 3000/8000 frames show no brightening. Test with `ca85968f`, `NOFLASH`, and Hide
Lightning Flashes on; find the source (sky-flash pass vs a vanilla lightmap/sky flash consuming
`skyFlashTime`). 1500 blocks is inside the original's 2000-block flash cutoff — a flash there may be
correct, but it must not light the whole terrain.

### 5. Storm fog visual tuning
Verified caused by the new fog (gone with NOFOG): a dark band along the horizon with vertical
streaks at the 64-block cell spacing — sky rays march the full 800 blocks under cover. Options:
fade density with distance for rays that end in sky, more steps / better noise, a smoother or finer
coverage map, lower cap for sky rays. Compare with the 1.20.1 `program/storm_fog.fsh` and the
preserved raymarch experiment; do not merge that until S1–S5 pass.

### 6. Parity re-runs (after 1–5)
`SHAKE FAST`, `A B C D F`, `STORM`, `STORM NOFLASH`; inspect every contact sheet. Done this session
with the transform fix: SHAKE normal (41 frames, no whole-field disappearance or dither; faint
stair-stepped shading at the top-left sky edge in later frames — unexplained).

### 7. 30-minute stress
`tools/claude-run.sh evidence-…-stress A B C D F LOOP` (LOOP ends when `run/devshot.request` is
deleted; stop it after 30 min). Record transform capacity, `transformWritesPeakPerFrame`, DEVMEM
heap/direct/RSS every 30 s, worst frame, failed chunks. So far: RSS 3.6–3.8 GB (SHAKE), 3.1→4.4 GB
in the first 90 s of STORM, 5.18–5.26 GB flat over the last 90 s of the 8-min NOFOG run; direct
56 → 108 MB (621 buffers); failed chunks 0 everywhere.

### 8. Record and hand over
Update `PORT-PROGRESS-2026-09-14.md` with exact evidence; install only after the evidence passes and
Jan asks (build, compare checksum, back up the installed jar first).

---

# Simple Clouds Fabric 26.2 — Claude handoff

## Objective

Continue the isolated Fabric 26.2 Simple Clouds port on Mony. Make the CPU
renderer visually stable, finish storm fog and lightning behavior, then
validate before installing another JAR in the real Modrinth profile.

## Important paths

- Candidate: `/home/jan/.cache/simpleclouds/port-candidate-tgkFnnmU`
- Real profile mods: `/home/jan/.local/share/ModrinthApp/profiles/Fabric 26.2/mods`
- Candidate launcher: `./dev-relaunch.sh`
- Candidate progress: `PORT-PROGRESS-2026-09-14.md`
- Original dirty repo (preserve): `/home/jan/.local/share/ModrinthApp/profiles/Fabric 26.2/local-mods/simple-clouds`
- Baseline and storm-fog preservation are documented in the progress file.

## Safety and scope

- Work only in the isolated candidate until a test passes.
- Preserve the original dirty repository and its storm shader files.
- Do not reset, discard, commit, or push Git changes.
- Do not kill the real Modrinth game or unrelated Symbiote/AI processes.
- Stop only `simpleclouds-devclient` when ending a dev test.
- Keep the installed real-profile JAR unchanged until the user explicitly asks
  for installation after relevant visual evidence passes.

## Current verified state

- Candidate compiles and `./gradlew build` succeeds (Gradle reports no unit
  tests; standalone tests are run manually).
- CPU chunk generation has local generation keys, shared snapped phases,
  continuous scroll offsets, pooled buffers, batch publication, and bounded
  generation summaries.
- 32x and normal SHAKE tests completed 41 frames without whole-field
  disappearance or the old dither/screen-door transition.
- Standard A/B/C/D/F captures complete. The F test now truly looks down from
  above the cloud layer; the old C/D brightness conclusion was withdrawn.
- The STORM harness now replaces saved formations with one deterministic
  cumulonimbus and rejects invalid NaN coordinates. Corrected STORM produced
  S1-S4 plus 60 S5 frames.
- Storm coverage tests pass for distant chunks, LOD 1/2/4/8, boundaries,
  partial coverage, and invalid camera coordinates.
- The candidate atmospheric shader has corrected std140 offsets, a reusable
  scene copy instead of same-texture feedback, correct aspect handling, and a
  downward-ray guard.
- Real profile was installed once at user request with SHA256
  `1fa5ff98efb5812d09d4b8e301a154895b36254d1058a6da3f9a328db327aa15`.
  A later candidate JAR is not installed.

## Remaining work, in order

### 1. Bound Dynamic Transforms allocations

The 64-view STORM run repeatedly resized the Dynamic Transforms UBO up to
65536 slots. This is a high-water allocation caused by one transform slice per
chunk draw and two scene passes; it did not crash, but it blocks a 30-minute
stress test.

Inspect `CloudsDrawPipeline` and `DynamicUniforms` usage. Replace per-draw
unbounded allocation with a bounded, fenced ring or a reusable transform UBO.
Retain distinct model-view, offset, and alpha values per draw. Verify no stale
uniforms are observed. Add a diagnostic cap and fail clearly if the cap is
exceeded; do not silently report success.

Validation: normal SHAKE, 32x SHAKE, STORM, then a 30-minute dev run. Record
peak transform slots, heap/direct memory, RSS, worst frame, and failed chunks.

### 2. Correct STORM scene placement and timing

S2/S5 currently reuse the ground camera height while moving far from the probe
origin. Add a terrain-height lookup at each target location and wait for that
area's terrain to render before capturing. Do not accept a screenshot while
terrain is still loading. Keep the deterministic cloud center and finite
coordinate checks.

### 3. Implement spatial storm fog and local lightning lighting

Current `drawStormFog` uses one camera-wide scalar and the existing sky flash is
screen-wide. Implement a depth/coverage-aware fog pass so only cloud-covered
view regions darken. Lightning contribution must be tied to the nearby bolt's
position and distance, applied per fog sample or screen region. Distant strikes
must produce thunder/sound behavior without a nearby flash. Respect
`stormFogLightningFlashes` and the existing Hide Sky Flashes gate.

Inspect the preserved raymarch/shadow candidate separately. Do not merge it into
the stable renderer until S1-S5 pass with no shader/uniform warnings. Check
reversed-depth conventions against an actual captured depth value; do not infer
them from names.

Validation: S1 edge, S2 under-cloud, S3 side, S4 above, S5 timed sequence;
compare with lightning enabled/disabled and a distant-only strike. Check the
log for render-pass, sampler, uniform, and shader errors.

### 4. Re-run parity and performance evidence

After fixes, run `./dev-relaunch.sh` with `SHAKE`, `SHAKE FAST`, `A B C D F`,
and `STORM`. Inspect every contact sheet. Run the standalone CPU seam, empty
chunk, buffer-pool, generation-key, shader-face, and storm-coverage tests.
Record observed results in `PORT-PROGRESS-2026-09-14.md`; label suspected and
verified findings separately.

### 5. Install only after evidence

Build the JAR, compare its checksum, preserve the current real-profile JAR,
and install only when the relevant visual and stress checks pass. Never claim
visual parity from a successful build alone.

## Known unrelated dev warnings

Offline Minecraft authentication/Realms errors, missing narrator `flite`,
OpenAL failure, an old Jade game-rule key, and Gradle's deprecation warning are
known isolated-environment warnings. They are not Simple Clouds render errors,
but audio fidelity is not validated.

## Reporting format

For each attempt report: changed files, exact command, build result, runtime
log result, screenshots inspected, memory/transform peak, observed issue,
confidence (observed/suspected/verified), and the next discriminating test.

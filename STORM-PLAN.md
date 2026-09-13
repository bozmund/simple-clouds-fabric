# Storm plan (Jan, 2026-09-13 19:40) — lightning flashes and the storm cloud

Jan played the real profile (jar from 18:45) and reported two problems, with four screenshots
(`Fabric 26.2/screenshots/2026-09-13_19.37.41.png`, `_19.38.36`, `_19.38.49`, `_19.38.56`):
1. **Lightning from a storm far away flashes the whole screen.**
2. **The big storm cloud (cumulonimbus) looks broken:** a flat grey wall with stepped edges, a
   perfectly straight vertical cut on one side, and from below smooth smeared grey blobs with
   thin bright streaks and stripes instead of cubes. Small dark fragments float near the horizon.
The ordinary white clouds look right. None of this showed in the standard views because no test
scene contained a storm cloud up close.

**First finish step 7** (the 30-minute memory run in the real profile and the FPS on/off
measurement), then do this plan. Same ground rules as before: one editor, no subagents on the mod
files, read every screenshot, commit per step, stop the dev client after every check, do not mark
anything complete, stop at the end and report to Jan.

## Step 0 — Storm views (so the problems can be seen and proven fixed)
Add DevShot tokens that spawn a cumulonimbus formation at a fixed offset from the player (the
original spawning config: `SimpleCloudsCloudSpawningConfigProvider`, cumulonimbus radius
6000-10000) and take, at noon with time and weather pinned:
- **S1** from the ground, pitch 0, with the formation's edge in view (the "wall" of Jan's shot 1);
- **S2** from under the formation, looking up (shots 3-4);
- **S3** from y≈260 beside it (shot 2);
- **S4** from above it;
- **S5** a 60-second sequence (one frame every 2 s) from the ground while its lightning runs, with
  a log line per strike: distance to the camera and the flash strength applied.
Compare S1-S4 with `docs/reference/modrinth-cumulonimbus.png`, `modrinth-cumulonimbus-dh.png` and
`modrinth-distant-storm-dusk.png`. Keep S1-S5 in the standard views from now on.

## Step 1 — Lightning flashes (the whole screen flashes for far storms)
**What the port does** (`client/renderer/WorldEffects.java`):
- `spawnLightning` sets `flashTicks = 24 + (seed & 31)` (1.2-2.75 s) for **every** strike, at any
  distance (strikes spawn anywhere in a 20,000-block square, `LIGHTNING_SPAWN_DIAMETER`), and even for
  sound-only strikes (the flash is set before the `onlySound` return).
- `flashStrength` drives `lightningMul = 1 - flash * 0.9` (`SimpleCloudsRenderer`, storm-fog draw) and
  `getDarkenFactor`: a global brightening of the full-screen storm darkening.

**What the original does:**
- Sky flash only for a bolt within `CLOSE_THUNDER_CUTOFF` (2000 blocks) whose fade is still > 0.5,
  and only `level.setSkyFlashTime(2)`: 2 ticks (orig. `WorldEffects.renderLightning`, l.183-184).
  `SimpleCloudsRenderer.getCloudColor` adds `(skyFlashTime - partialTick) * LIGHTNING_FLASH_STRENGTH`
  to the cloud colour factor, a short brightening of the clouds, not of the whole screen.
- Inside storm fog, flashes are **local**: `storm_fog.fsh` gets the bolts' positions and alphas
  (`LightningBolts` buffer, `TotalLightningBolts`) and lights the fog around each bolt; it can be turned
  off with `stormFogLightningFlashes`.
- Bolts fade with distance (`getFadeFactorForDistance(dist)` when rendered).

**Fix:**
1. No flash for sound-only strikes.
2. The global flash: only for a rendered bolt within 2000 blocks with fade > 0.5, and as the original's
   short sky flash (2 ticks via the 26.2 equivalent of `setSkyFlashTime`, applied to the cloud colour
   like `getCloudColor`), not a 1-3 s multiplier on the storm fog.
3. Storm-fog flashes: port the per-bolt local lighting (bolt positions + alphas to the storm-fog shader;
   a small fixed array in a UBO is fine) and honour `stormFogLightningFlashes`. Remove the global
   `lightningMul`.
4. Respect Minecraft's own accessibility option "Hide Lightning Flashes" (find its 26.2 name with javap):
   when it is on, no sky or fog flash at all.
**Proof:** S5 with a storm 3000+ blocks away: no frame changes brightness across the screen; log shows
flash 0 for every far strike. A strike within 2000 blocks: a single short flash. Under the storm: the
fog lights up around the bolt only. Hide Lightning Flashes on: no flash.

## Step 2 — Thunder (same code path, fix it while there)
The port plays close thunder only within 16 blocks and immediately. The original: close thunder within
2000 blocks (`CLOSE_THUNDER_CUTOFF`), attenuation from `thunderAttenuationDistance`, pitch/volume fade
between `THUNDER_PITCH_FULL_DIST` (3000) and `THUNDER_PITCH_MINIMUM_DIST` (5000), and a delay of
`dist / SOUND_METERS_PER_SECOND` seconds (`playDelayed`). Port these. **Proof:** log per strike: distance,
sound chosen, delay, pitch.

## Step 3 — The smeared, streaky storm cloud (transparency)
**Evidence:** the original draws transparent cubes with **weighted blended order-independent
transparency** (`client/framebuffer/WeightedBlendingTarget.java`, `clouds_transparency` shaders, the
`final_composite` post pass). The port's transparency pass blends them directly into the main target in
arbitrary order (`CloudsDrawPipeline` transparency pipeline: `BlendFunction.TRANSLUCENT`, no depth write,
no sort). A storm cloud has many overlapping transparent cubes (large `transparency_fade`), and unsorted
alpha blending gives exactly the smooth grey smears and bright streaks in Jan's shots 3-4.
**Fix:** port the weighted blended OIT: accumulate + revealage targets for the transparent cubes, then a
composite over the scene (as the original's `final_composite`), keeping the depth test against the scene
(clouds behind terrain stay hidden). **Proof:** S2 and S3 show defined cube structure through the
transparent edges, no streaks, and the same image when the camera turns (order independence).

## Step 4 — The straight vertical cut and the stripes
- **Cut (shot 1):** a storm cloud ending in a straight vertical line is a chunk border. Find out which:
  a chunk culled by a frustum/cull bounding box whose Y extent is smaller than the storm cloud's real
  height (check the AABB against the generated instances' min/max Y), a chunk not generated yet or
  dropped by the generation budget, or a neighbouring LOD ring with no geometry there. Log per chunk:
  LOD, AABB, instance count, culled or not.
- **Stripes and thin lines (shots 3-4):** look for coincident faces where LOD rings meet (the first ring's
  `noOcclusion` faces drawn on top of the core's faces) and for z-fighting between opaque and transparent
  cubes at the same position.
**Proof:** S1 shows the formation's edge as cloud shapes, never a straight line; S2/S3 without stripes;
the chunk log shows no visible chunk culled.

## Step 5 — Smooth cloud motion and the render thread (Jan: "cloud moving is not multithreaded")
Generation is off-thread (12 `simpleclouds-chunkgen-N` workers), but motion is not smooth and the
render thread does costly work while the clouds update:
- **Motion comes in 8-block jumps.** Since A2 a chunk only moves when it is regenerated, after the wind
  scroll drifts `SCROLL_REGEN_THRESHOLD` = 1 cloud unit (8 blocks); between regenerations it stands still.
- **Neighbouring chunks go out of phase.** Every chunk shares one scroll, so they all go stale together,
  but only `CHUNK_ENQUEUE_BUDGET` = 6 are queued per frame, nearest first. Neighbours are regenerated at
  different times from different scroll values, so their shapes do not line up at the border: a straight
  vertical seam, probably the cut in Jan's shot 1 (check this against step 4).
- **The render thread churns GPU buffers.** It polls up to `CHUNK_POLL_BUDGET` = 12 finished chunks per frame
  and for each creates two new GPU buffers (`RenderSystem.getDevice().createBuffer`), uploads, and closes
  the old ones.
- **Log spam.** Every chunk generation logs at INFO: about 21,000 lines per worker in the real profile's
  30-minute run; `logs/latest.log` reached 46 MB.

**Fix:**
1. **Continuous motion:** between regenerations, draw each chunk offset by the wind drift since its geometry
   was sampled (`(scrollNow − genScroll) · 8` blocks in XZ, per chunk, applied as a per-draw offset). This is the
   smoothing the ADDENDUM allowed; regeneration still happens (it brings the shape change from `Wiggle` and the
   formation outlines up to date). Check the sign against the noise sampling.
2. **No seams:** regenerate against a shared, snapped scroll phase and swap a ring of neighbours together
   (double-buffer: keep drawing the old meshes until every chunk of that phase is ready), so adjacent chunks
   always come from the same phase.
3. **Cheap uploads:** keep each chunk's GPU buffers and write into them when the new data fits (grow only when
   needed); limit uploads per frame by bytes/time (e.g. ≤ 2 ms), not by count; nothing allocated per frame.
4. **Logs:** per-chunk lines to DEBUG; at most one INFO summary every ~30 s (chunks generated, average and max
   time, uploads per frame).

**Proof:** frame-time p50/p99 and the worst frame over 2 minutes, at normal wind and at 32× wind, before and after
(dev client and real profile); a frame-by-frame sequence (one frame every 0.25 s for 10 s) showing continuous drift
with no jumps and no seams at chunk borders; `latest.log` growth per 10 minutes.

## Step 6 — Storm look against the reference
With steps 1-4 done, compare S1-S4 with the references: a towering column of cubes, dark base and lighter
top (`storm_start`/`storm_fade_distance` brightness), storm fog under it, rain, lightning bolts inside.
Also check the small dark fragments near the horizon in Jan's shot 3: are they part of a far storm cloud
(then they must fade with distance fog like other clouds) or stray geometry.

## Step 7 — Real profile and report
Install (`./dev-relaunch.sh --install`, never over a running game), repeat S1-S5 in the real profile with
Distant Horizons, re-check memory (the step-7 numbers) and FPS near a storm. Update PORTING.md and
VISUAL-PARITY-RESULT.md, commit, then STOP and report to Jan with before/after images (Jan's four shots vs
S1-S4) and the flash log. Jan decides whether it matches.

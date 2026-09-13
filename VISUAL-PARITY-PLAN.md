# Visual parity plan (Jan, 2026-09-13) — the port is NOT complete

Jan looked at the installed mod and at your last devshot (`/tmp/sc-latest.png`, 2026-09-13 00:11):
**clouds sit at sea level, they are much less detailed than the real mod, and they pop in out of
nowhere.** Your parity audit marked these areas done because every check was a noon devshot
looking straight up, which cannot show altitude, distance or motion. Below is what is wrong, the
evidence from the original source (`/tmp/simple-clouds-src`), the fix, and how each step is
proven. Work through the steps in order.

## Ground rules
- **One editor.** Do not run subagents on the mod files. If a file changes under you, stop and check
  `git diff` / `git log` before you continue.
- **Every step ends with the standard views (step 0) in the dev client**, compared with the reference
  images, then one commit (`git add -A && git commit`, allowed as before).
- **Do not mark the task complete and do not write "port complete".** When step 8 is done, STOP and
  report to Jan with the before/after images. Jan decides whether it matches.
- Keep `CpuCloudGenerator` as the generator (the GPU path stays shelved).

## Step 0 — Reference images and standard views (do this first)
1. Download the official Simple Clouds screenshots (the mod's Modrinth and CurseForge gallery pages,
   plus the images in the original repo's README/docs if any) into `docs/reference/` with a
   `docs/reference/README.md` saying what each one shows.
2. Extend `DevShot` / `devshot.request` so one run can take these fixed views (same world, same seed,
   time set to noon):
   - **A (horizon):** player at sea level (y≈64-70) on a beach, pitch 0, looking over open water.
   - **B (landscape):** y≈100 over land, pitch -15.
   - **C (up):** straight up (the view you have now).
   - **D (inside/above):** y≈260, pitch -20 (above the cloud layer, looking across its top).
   - **E (motion):** view A twice, 10 s apart, saved as two files.
3. Save each run's views as `/tmp/sc-view-<A..E>.png` and READ every image you take (including the
   ones from `--install` runs). Write one line per image: what you see vs the reference.

## Step 1 — Cloud altitude (clouds at sea level)
**What you did:** commit `b80e6d2` anchored the cloud volume to the camera
(`baseU = floor((camY - cloudHeight) / 8 / 16) * 16`, comment "anchored cloudHeight BELOW the camera,
so it follows the player's altitude"). With the player near y=70 the volume base is near y=-58, so the
low layers of stratus / nimbostratus / cumulonimbus land at and below sea level. Commit `3d474b0`
then explained Jan's ground-level cloud away as "base layers at y=0 by design".

**What the original does:** the cloud field is fixed in world space at a configurable altitude.
- `SimpleCloudsRenderer.translateClouds` (orig. l.985-988): `stack.translate(-camX, -camY + cloudHeight, -camZ)`
  then `stack.scale(CLOUD_SCALE, CLOUD_SCALE, CLOUD_SCALE)`. A cube at cloud-space `(x, y, z)` is drawn at
  world `(8x, cloudHeight + 8y, 8z)`.
- `cloudHeight` = `SimpleCloudsConfig.CLIENT.cloudHeight` / `CloudManager.getCloudHeight()`, **default 128**
  (`clientSideCloudHeight`, "the render Y offset for the clouds").
- `originY = (camY - cloudHeight) / scale` (orig. l.1051) is only the **camera position in cloud space**
  (LOD centre, culling, `genTick`), never the volume base.
- Layer heights (`HeightOffset`, `Height` in the cloud types) are cloud-space Y **above** `cloudHeight`:
  a layer at y=0 starts at world y=128.

**Fix:**
1. Generate in fixed cloud-space Y: from 0 (or the lowest `HeightOffset` of the active types) to the
   highest `HeightOffset + Height`. Drop `baseU` from the generation and from `BandCoord`.
2. Place the mesh at world `Y = cloudHeight + 8·y` (put the `-camY + cloudHeight` offset in the view
   translation like `translateClouds`, or add `cloudHeight` to the instance Y).
3. Apply the same anchoring everywhere the old camera-relative base leaked in: the shadow-map stack
   (orig. `createShadowMapStack` translates by `-cloudHeight`), storm fog, rain/`RAIN_VERTICAL_FADE`,
   lightning spawn height, the atmospheric layer, and any server-side cloud lookup at a position.
4. Correct the anchoring paragraphs in PORTING.md ("CLOUD VOLUME ANCHORING") and the "by design" note.

**Proof:** log the min/max world Y of the generated instances (min must be ≥ cloudHeight + 8·lowest
HeightOffset, ≈128 by default). Views A and B: no cloud below ~y=120, cloud bases float well above
the sea. View D: flying from y=100 to y=260 you pass under, into and above the layer, and the clouds
stay put (they must not move up and down with you).

## Step 2 — Render distance and level of detail (clouds end a few hundred blocks away)
**What you did:** a 2×2 or 3×3 grid of 32×32-unit bands (256×256 blocks each) at full detail, so
clouds exist only within ~256-384 blocks of the player and the sky beyond is empty.

**What the original does** (`client/mesh/LevelOfDetailOptions`, `client/mesh/lod/LevelOfDetailConfig`,
`CloudMeshGenerator`): chunks of `CHUNK_SIZE = 32` cloud units (256 blocks), a full-detail core plus rings
of coarser chunks:
- `HIGH` (the default, `levelOfDetail` config): core 8×8 chunks (2048 blocks wide) at cube size 1 unit,
  then rings `LevelOfDetail(2, 4)`, `(4, 3)`, `(8, 2)` (cube size 2, 4, 8 units; spread in chunks).
  Effective radius = 4 + 2·4 + 4·3 + 8·2 = 40 chunks ≈ **10,000 blocks**.
- `MEDIUM` = core 4 + `(2,3) (4,4) (8,2)`, `LOW` = core 4 + `(2,1) (4,3) (8,3)`.
- Each chunk is generated with `Scale = lodScale` (orig. `CloudMeshGenerator` l.904-921: the grid spacing and
  cube radius grow with the LOD), and the first ring does not occlude against the core
  (`PreparedChunk ... noOcclusion ? 5 : -1`, `DoNotOccludeSide`).
- `LevelOfDetailConfig` / `PreparedChunk` / `LevelOfDetail` are already in your tree: use them.

**Fix:**
1. Replace the band grid with the LOD chunk layout from `LevelOfDetailConfig.prepareChunks()` (centred on
   the camera in cloud space, `originX/Z`), chunk by chunk, nearest first.
2. `CpuCloudGenerator.generate` per chunk at `lodScale`: sample the noise at cell spacing `lodScale` and emit
   cubes of that size (radius `lodScale/2` units); keep region masks, edge fade and transparency per chunk.
3. Keep generation off the render thread; use a small worker pool (e.g. half the cores) and per-chunk
   instance buffers. Limit Y to the real layer span (step 1) — do not generate 256 units of empty air.
4. Honour the `levelOfDetail` config (HIGH/MEDIUM/LOW) so a slower PC can pick less.

**Proof:** views A, B and D show clouds out to the horizon, visibly coarser with distance, with no ring or edge
where coverage stops. Log chunk counts per LOD and generation time; the frame rate must not drop while the
field fills (fill order: nearest first). A first full fill within ~10-20 s is fine if it fades in (step 3).

## Step 3 — Fade-in and distance fog (clouds pop in out of nowhere)
**What you did:** a finished band replaces the old one instantly; `CHUNK_FADE_IN_ALPHA_PER_TICK` is defined
but never used; `SimpleCloudsRenderer` never computes the cloud fog distances.

**What the original does:**
- **Chunk fade-in:** `MeshChunk` alpha starts at 0 when a chunk gets a new mesh and rises by
  `CHUNK_FADE_IN_ALPHA_PER_TICK = 0.2` per tick (5 ticks); it is drawn with
  `RenderSystem.setShaderColor(r, g, b, chunk.getAlpha(partialTick))` (orig. l.734, 791), and `clouds.fsh`
  discards fragments with a Bayer dither (`if (ColorModulator.a < r) discard`). Clouds dissolve in.
- **Distance fog:** `renderDistance = cloudAreaMaxRadius · CLOUD_SCALE · factor` (+ `ModifyCloudRenderDistanceEvent`),
  `fogStart = renderDistance / 4`, `fogEnd = renderDistance`, with the storm overrides (orig. l.1005-1044), and
  `setCullDistance(fogEnd / CLOUD_SCALE)`. `clouds.fsh` mixes the colour to `FogColor` with
  `smoothstep(FogStart, FogEnd, fogDistance)`; far clouds melt into the sky.
- **Formations grow:** `CloudRegion.radius` starts at 0 and grows over `growTicks`; new formations swell into view.

**Fix:** port the per-chunk alpha (reset on every new mesh, +0.2 per tick, lerped by partial tick) into the
draw (`ColorModulator.a` per chunk, dither in the fragment shader); port the fog computation verbatim and feed it
to `CloudsDrawPipeline.writeFog`; keep drawing a chunk's old mesh until its new one is ready, then fade the new
one in. Check that region growth reaches the mask (`getRadius(partialTick)`).

**Proof:** view E (two shots 10 s apart): nothing appears or vanishes abruptly; far clouds are tinted toward the sky
colour; a debug log of chunks with alpha < 1 while the field fills. Watch a newly spawned formation (command or
DevShot helper) grow instead of appearing.

## Step 4 — Wind drift and morphing (clouds are frozen)
**What you did:** every band is generated with scroll and wiggle **0**
(`generate(..., BAND_SCALE, 0.0F, 0.0F, 0.0F, 0.0F, ...)`), so the noise field never moves; only the formation
masks slide across frozen noise, which also makes clouds appear and disappear in place.

**What the original does:** `meshGenerator.setScroll(cloudManager.getScrollX/Y/Z(partialTicks))` and `Wiggle`
(orig. l.273); `cube_mesh.comp` samples `pos / scale + Scroll / scale` (l.186-188), so the clouds drift with the
wind and slowly change shape; speed = `clientSideSpeedModifier`.

**Fix:** pass the cloud manager's scroll (and wiggle) into generation, regenerate chunks continuously at the
original's pace (`GenerationInterval` — DYNAMIC / TARGET_FPS in the original config), and let the step-3 fade hide
the swaps. If regeneration cannot keep up, translate each chunk's instances by the scroll change since it was
generated (check the sign against the noise sampling) until its next regeneration.

**Proof:** view E shows the clouds drifted in the wind direction between the two shots, with no popping.

## Step 5 — Look and detail against the reference
Compare views A-D with `docs/reference/` and fix what differs, at least:
- **Shading:** `UseNormals` (`cubeNormals` config) with `Light0/Light1` directional light and per-cube
  `brightness` (`DarknessColorModifier` for storm types) — sides and bottoms darker than tops as in the reference.
- **Transparent cubes:** the original emits them only where the noise is in `(-TransparencyFade, 0)` and only
  within `transparencyRenderDistancePercentage` (default 50 %) of the view; they are centred in their cell
  (`cube.x = x + cubeRadius`). In your last devshot the translucent shells around the opaque cubes look larger and
  more frequent than in the reference — check alpha, size and range.
- **Colour:** time-of-day cloud colour and `FogColor` mixing; storm darkening.
Write the comparison per view into `VISUAL-PARITY-RESULT.md`.

## Step 6 — Everything that used the old anchoring or the band grid
Re-verify after steps 1-4: terrain cloud shadows, storm fog, rain start/stop under clouds, lightning spawn height,
atmospheric layer, the cloud previewer screen, `/simpleclouds` commands that report positions, server-side
persistence (`CloudData`). Fix what moved.

## Step 7 — Real profile
`./dev-relaunch.sh --install`, then the same views in Jan's real profile (with Distant Horizons installed — check
that clouds still sort correctly against DH terrain far away). Read every screenshot.

## Step 8 — Report and stop
1. Update PORTING.md honestly (the anchoring, range, fade and drift rows were wrong; say so).
2. `VISUAL-PARITY-RESULT.md`: for each step, before/after images (paths), the reference image you compared with,
   and what still differs.
3. Commit, then STOP and send Jan one short message with the result and the image paths. Do not mark the task
   complete; Jan decides.

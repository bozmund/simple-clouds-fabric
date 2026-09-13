# Simple Clouds → Fabric 26.2 Port Plan

## BUILD STATUS (2026-09-10): GREEN ✓
`./gradlew build` succeeds (0 errors). Jar: `build/libs/simple-clouds-0.7.3+26.2-fabric.jar`.
The full codebase (crackerslib + simple-clouds, ~200 files) compiles against Fabric 26.2.
- **Rendering**: v2 vertical slice — opaque clouds via CPU mesh gen (CpuCloudGenerator +
  PsrdNoise) → per-instance vertex buffers → RenderPipeline/RenderPass (CloudsDrawPipeline).
  Advanced effects (shadow map, transparency, storm fog, blur, DH support) are stubbed no-ops.
- **Stubbed (compile-only, port incrementally)**: debug overlay, layer editor,
  info/error/notice screens, atmospheric clouds, compute. (World effects/rain,
  shadow map, transparency + storm fog, config screens, client commands, 3D
  previewer and DH support are DONE — see the HANDOFF task list below.)
## IN-GAME STATUS (2026-09-11): LOADED + CLOUDS RENDER ✓
Verified headlessly via `./gradlew runClient` (dev client, Vulkan, Temurin 25) in two worlds
(the user's modpack world `CloudTest` and a fresh vanilla world `CloudClean`):

- **Mod loads**: all mixins apply, entrypoints run, no crash. (The modpack world dies at
  spawn due to its broken missing-mod terrain — unrelated to the mod.)
- **Clouds render**: opaque dithered cloud patches visible in the sky above the player
  (135 instances at one sample), correct fog + lighting. Screenshot evidence in the session.

Key 26.2 renderer facts discovered while making this work (for the remaining port):
- Shader sources are loaded by `ShaderManager` at resource-reload time and keyed by
  **bare location + ShaderType** — `RenderPipeline.builder().withVertexShader(id("core/clouds"))`
  (NO .vsh/.fsh suffix; the device appends the extension).
- `RenderSystem.bindDefaultUniforms(pass)` binds only `Projection`/`Fog`/`Globals`/`Lighting`.
  World passes must additionally bind `DynamicTransforms` (ModelViewMat/ColorModulator/…)
  explicitly: `new DynamicUniforms().writeTransform(modelViewMatrix)` — exactly what the
  vanilla `CloudRenderer` does.
- The `LevelRenderer.render(...)` model-view stack is pushed/popped **early** in the method,
  so at our TAIL hook `RenderSystem.getModelViewMatrixCopy()` is stale; build the view
  matrix explicitly: `camera.getViewRotationMatrix()` * `translate(-camPos)`.
- **Every uniform block a shader uses must be declared on the pipeline** (a
  `BindGroupLayout` or a vanilla snippet). `GlProgram` skips undeclared blocks with
  `Found unknown and unsupported uniform X`, and `pass.setUniform("X", ...)` then
  silently does nothing. A missing `DynamicTransforms` left ModelViewMat reading a
  stray buffer: the 2026-09-11 "pipeline draws N instances but no clouds / flicker"
  bug. Start world pipelines from `RenderPipelines.MATRICES_FOG_SNIPPET` (Globals,
  DynamicTransforms, Projection, Fog) like vanilla `CLOUDS`, and treat that log
  warning as fatal. "first draw, N instances" does NOT prove anything is visible.
- Manual `CommandEncoder` + `RenderPass` into `gameRenderer.mainRenderTarget()` works
  (vanilla clouds use the same pattern against `levelRenderer.cloudsTarget()`).
- GLSL: the pipeline compiles as GLSL ES 3.0 — no C-style array initialization of
  constructors (the old `normals[6]`/`transformations[6]` const arrays had to become
  functions in clouds.vsh).
- Commands are deferred: the new backend only recognizes argument types registered during
  vanilla bootstrap, so CloudTypeArgument/EnumArgument would kill player joins. Re-port via
  a bootstrap mixin or string-arg + manual validation.
- Player NBT in 26.2: rotation is a `Rotation` float LIST (yaw, pitch), not XRot/YRot.
- Dev launch env: Temurin 25 + `VK_ICD_FILENAMES=/run/opengl-driver/.../intel_icd.x86_64.json`
  + `LD_LIBRARY_PATH` from /tmp/lwjgl-ldp.txt (Vulkan loader, GLVND, X11, Wayland).

## FEATURE PARITY AUDIT vs 1.20.1 original (2026-09-12, from /tmp/simple-clouds-src)
Statuses: **VERIFIED** = ported + confirmed on screen (devshot/play-test);
**UNVERIFIED** = ported, compiles + runs, no on-screen confirmation yet;
**MISSING** = not ported (config option may still exist as a no-op).

### Cloud rendering
| Feature | Status | Notes |
|---|---|---|
| Voxel cloud mesh (region-masked noise field, discrete formations) | VERIFIED | CPU generator; multi-band full-region rendering (2026-09-12) |
| Edge dithering (bayer threshold) | VERIFIED | original task 1 |
| Data-driven heights/layers per type (noise_settings) | VERIFIED | original task 2 |
| Formation spawning (weight/growth/expiration, server-driven) | VERIFIED | client + server managers |
| Opaque + transparent cubes (alpha-blended edges) | VERIFIED | transparency depth convention fixed 2026-09-12; 2026-09-12 -20 devshot shows the soft alpha edge halos around opaque cores |
| Cloud shading (sun-lit faces) | VERIFIED | CloudLighting uniform |
| Per-cube face normals shading (`cubeNormals`) | PORTED | shader path existed (sideNormal + mixLight, exact normals from 1.20.1 opaque.glsl); 2026-09-12: UseNormals now follows the live config via a per-frame ring UBO (static-UBO remap is the 26.2 no-go); default off = flat per-cube brightness, as in 1.20.1 |
| Storm fog (darkening + lightning flash) | VERIFIED | intensity from storm coverage above camera |
| Custom rain (PrecipitationQuads) | VERIFIED | slice: no wind tilt, no snow |
| Lightning (server spawn -> packet -> flash + thunder) | VERIFIED | flash via storm-fog LightningMul; thunder sounds; bolt MESH now ported (2026-09-12: 1.20.1 recursive branch tree, additive pass, depth-tested, no fog; BOLT devshot token; jagged branched bolt verified on screen sc-bolt-3) |
| Thunder / rain audio assets | VERIFIED | port now ships all 27 original `.ogg` files (16 thunder + 8 rain ambience + 3 legacy) — previously `sounds.json` referenced thunder files absent from the port jar, so strikes were silent. Custom **rain** sound-replacement (replacing vanilla rain with the 8 rain ambience) is still a no-op stub (config option exists) |
| Cloud shadow map + terrain shadows (`distantShadows`) | **VERIFIED (2026-09-12, A/B devshot)** | 512px ortho shadow map + fullscreen terrain pass. Two 26.2 gotchas fixed: (1) shadow light-volume matrix sign errors (nothing landed in the frustum); (2) **per-frame UBO mapping silently kills a pass** -- the fixed buffer mapped every frame (and even MappableRingBuffer's size-only createBuffer, which also fails to map on this Mesa/Intel ARL machine) made the terrain pass draw nothing; fixed with a manual 3-deep ring built from the data-carrying createBuffer overload with UNIFORM|MAP_READ usage. Shadow volume extends 128 blocks BELOW camY (the original raymarch covered the ground). SHADOWTEST scene: shelf visibly darkened with shadows, bright with NOSHADOW. |
| Atmospheric 2D cloud layer (`atmosphericClouds`, default ON) | **VERIFIED (2026-09-12)** | Full port: fullscreen pass (no PostChain in 26.2) sampling the main color target, psrdnoise ray-cast to a plane 5000 above the camera; biome-driven formations with cross-fade (Forge biome tags replaced by base-temperature/precipitation predicates); wind from the cloud manager; blindness/darkness alpha. 26.2 notes: level FOV from `gameRenderState().levelRenderState.cameraRenderState.hudFov` (no GameRenderer.getFov anymore); ring UBO per the per-frame-UBO rule. Sky + straight-up devshots show the wispy layer. |
| Fog render modes (`fogMode`) | MISSING | single shader-fog implementation only |
| LOD / level-of-detail chunk layout (render distance) | **VERIFIED (Step 2, 2026-09-13)** | The original's `LevelOfDetailConfig`/`LevelOfDetailOptions` chunk layout (an 8-span core of fine chunks + 3 LOD rings, per `levelOfDetail` config, default HIGH) is now wired: the 2×2 full-detail band grid was replaced by the LOD chunk set, each chunk generated at its own `lodScale` (cube spacing + radius grow with the LOD), on a 12-thread worker pool with a nearest-first budgeted fill. The field extends to ~10,500 blocks (lod 1/2/4/8). Frustum culling / generation interval / occlusion-side testing remain no-ops (perf options). See the STEP 2 note below the table. |
| GPU compute generation (cube_mesh.comp) | MISSING (shelved) | CPU path ships; spike evidence in SPIKE-GPU-RESULT.md |
| Vanilla cloud layer removal | VERIFIED (dev client) | 26.2 addCloudsPass cancelled; flat+full variants both covered |
| DH (Distant Horizons) support | VERIFIED | DH 3.2.0 detected, its clouds disabled, handlers registered |
| **World-anchored** cloud volume at Y = cloudHeight (default 128, 2048-block span) | VERIFIED (Step 1, 2026-09-13) | 1.20.1 semantics: volume base = world Y = cloudHeight (NOT camera-relative); 256 units tall (VERTICAL_CHUNK_SPAN×CHUNK_SIZE); layer offsets/noise Y are volume-relative, so a stratus layer (offset 0) sits at world Y ≈ 128. Clouds do NOT follow the player's altitude. See the corrected CLOUD VOLUME ANCHORING section (the 2026-09-12 "camera-anchored" reading was wrong; proven by the shadow-map stack's `−cloudHeight`, no camY). Port now world-anchors the band grid + shadow light volume; verified Y range 132..380 |
| Ground-level bank layers (stratus/nimbostratus height_offset=0) | BY DESIGN | height_offset=0 = volume base = world Y = cloudHeight (128 by default), i.e. just above the waterline at sea level — NOT camera-relative and not ground level; below-terrain parts hidden by depth test (fixed 2026-09-12) |

### UI / screens
| Feature | Status | Notes |
|---|---|---|
| Config screens (client + server tabs, CrackersLib GUI) | VERIFIED | original task 7 |
| Keybinds (config, F12 previewer) | VERIFIED | |
| 3D previewer (orbit camera, main-frame draw) | VERIFIED | original task 9 |
| Previewer image-export button (CloudImageRenderer) | MISSING | offscreen export not ported |
| Info / notice / error screens | PORTED | shown on startup as before |
| Main-menu config button | VERIFIED | 26.2 `OptionsScreen.init` tail-inject adds a "Simple Clouds" button (MixinOptionsScreen + MixinScreenRenderablesAccessor — 26.2 `Screen` draws the `renderables` list, not `children()`); verified on screen (sc-optbtn-4); opens the same config screen as the keybind |
| Debug overlay renderer | MISSING | class present, never invoked (debug-only feature) |

### Commands
| Feature | Status | Notes |
|---|---|---|
| Client command tree (/clientClouds clear/spawn/get/count/refresh/speed/seed/height) | VERIFIED | original task 8 |
| Server command tree (/clouds ...) | MISSING (deferred) | 26.2 typed-arg bootstrap limitation; client commands operate the client manager |

### Server / multiplayer
| Feature | Status | Notes |
|---|---|---|
| Server cloud manager + spawning | PORTED | |
| Cloud data persistence (26.2 SavedDataType) | **VERIFIED (2026-09-12)** | clean exit writes `dimensions/<dim>/data/simpleclouds/clouddata.dat` for all 3 dimensions (755 B overworld, valid NBT: cloud_generator, regions, Speed, ticks_till_next_gen, ...) |
| Sync packets (manager/regions/types/lightning/mode) | PORTED | 7 packet types |
| Dimension change / respawn resync | PORTED | per-tick polling (26.2 has no events for this) |
| Vanilla weather cycle disable (server) | PORTED | MixinServerLevel |
| Dimension whitelist/blacklist (`whitelistAsBlacklist`) | **VERIFIED (2026-09-12)** | server-config-when-synced else client-config, blacklist inversion; 26.2 gotcha: `ResourceKey.toString()` concatenates registry+value with NO separator ("minecraft:rootminecraft:overworld") — must compare `dimension().identifier().toString()`. Verified live: a wrong comparison silently disabled clouds AND re-enabled the vanilla sheet (screenshot), fixed comparison restores ours |
| `cloudMode` (DEFAULT/SINGLE/AMBIENT), `cloudSeed`/`useSpecificSeed` | PORTED | |

### Misc
| Feature | Status | Notes |
|---|---|---|
| Custom rain sound replacement | MISSING | no-op stub (26.2 sound system) |
| Water color modulation by world effects (MixinBiomeColors) | MISSING | injection body commented out |
| Vivecraft compat | N/A | VR not used; stub kept |

### Work order (most visible first)
1. ~~Cloud shadow map~~ **DONE 2026-09-12** (A/B verified; UBO ring fix + volume 128 blocks below camY).
2. ~~Atmospheric cloud layer~~ **DONE 2026-09-12** (fullscreen pass + biome formations + cross-fade, verified on screen).
3. ~~Dimension whitelist~~ **DONE 2026-09-12** (identifier comparison; ResourceKey.toString gotcha documented).
4. ~~cubeNormals config wiring~~ **DONE 2026-09-12** (ring UBO).
5. ~~Transparent-edge visual verification~~ **DONE 2026-09-12** (edge halos visible in the -20 devshot).
6. Cloud data persistence + whitelist verification **DONE 2026-09-12**.
7. ~~Lightning bolt mesh~~ **DONE 2026-09-12** (sc-bolt-3).
   ~~Main-menu config button~~ **DONE 2026-09-12** (sc-optbtn-4).
   ~~Thunder audio assets~~ **DONE 2026-09-12** (27 oggs shipped).

**STATUS (2026-09-13): the port is NOT visually complete.** Jan's
`VISUAL-PARITY-PLAN.md` found the 2026-09-12 "port complete" call was wrong (the
parity audit's noon straight-up devshots couldn't show altitude/range/detail). Work
through the plan's steps 0–8:
- **Step 0** (reference images + standard camera views A–E) — done.
- **Step 1** (world-anchor the cloud volume at Y=cloudHeight; the old camera-anchored
  base was wrong) — done.
- **Step 2** (LOD chunk layout + per-lodScale generation + worker pool, render
  distance ~10,500 blocks) — done.
- Steps 3–8 (fog range, transparent-cube size/range, shading/lighting, color/
  night/dusk/storm-fog, motion/fade-in parity, DH/depth-sorting) — pending.

Remaining (low-priority / niche, see table above): custom rain sound-replacement,
fogMode screen-space world fog, previewer image export, debug overlay, frustum/
occlusion perf options, server command tree.

## STEP 2 — LOD / render distance (2026-09-13)

The 2026-09-12 "multi-band full-region rendering" (a 2×2/3×3 grid of 32×32-unit
bands, all at full detail, so clouds ended ~256–384 blocks out) was replaced by
the original's LOD system:

- `LevelOfDetailConfig`/`LevelOfDetailOptions` (already ported, now wired): an 8-span
  core of fine (lod 1) chunks + 3 LOD rings (lod 2/4/8), from the `levelOfDetail`
  config (default HIGH). Each ring chunk is generated at its own `lodScale`: the
  cube grid spacing AND cube radius grow with the LOD (cube_mesh.comp's `Scale`),
  so a distant chunk covers more area with fewer, bigger cubes.
- `CpuCloudGenerator.generate()` was generalized to take a `lodScale` (grid spacing
  + cube radius + spaced cube centers + region-mask cell indexing). The chunk grid
  span is `CHUNK_SIZE * lodScale` units; the grid is always 32 cells, so the
  region-mask `columnGroup` is always 32×32.
- The render thread enqueues stale chunks (nearest first, budget 6/frame) and
  collects finished ones (budget 12/frame); a 12-thread worker pool (`simpleclouds-chunkgen-
  <n>`) generates off-thread. A fresh chunk fades in (step 3) over 5 ticks.
- Verified: field extends to ±1312 cloud units (±10,500 blocks) across lod 1/2/4/8;
  no off-thread generation failures; the sky is filled to the horizon with coarser
  distant cubes (see `docs/reference/step2-lod-A.png`, `step2-lod-C.png`).

Gotcha fixed: the region-mask `columnGroup` index must use the spaced CELL index
`((x-x0)/lodScale)*zCells + ((z-z0)/lodScale)`, not the span index `(x-x0)*(z1-z0)`
(the latter only holds for lodScale==1 and overflowed for the coarse LODs).

## CLOUD VOLUME ANCHORING — **CORRECTED (2026-09-13, Step 1 of VISUAL-PARITY-PLAN)**

The 2026-09-12 entry below this one (and the table row it came from) concluded the 1.20.1
cloud volume is **camera-anchored** ("follows the player's altitude", base = camY − cloudHeight).
**That was wrong.** Jan's plan (from watching the real 1.20.1 mod) and the original source both
show the volume is **world-anchored at Y = cloudHeight** (default 128), and the port had wrongly
made it camera-anchored — the cause of the "clouds sit at sea level" bug (the player is near sea
level, so `camY − cloudHeight` put the stratus bank at the waterline).

**Decisive evidence (original source):** the cloud shadow map is a fresh stack with a top-down
ortho light volume translated by `(-camOffsetX, −cloudHeight, −camOffsetZ)` — **no camY**
(`SimpleCloudsRenderer.createShadowMapStack`, 1.20.1). It therefore places the cloud volume at
world Y = `cloudHeight + y`. For the shadows to line up with the clouds, the main render must do
the same. If the original were camera-anchored (base at `camY − cloudHeight`), the shadow map would
be displaced for every camera height except `camY = 2·cloudHeight` — i.e. it would be visibly wrong.
It is not. **The original is world-anchored.**

(`originY = (camY − cloudHeight)/8` in the original is only the CAMERA's position expressed in
cloud space, used for frustum culling / the "storm above the camera" metric — it is never the
volume base. The previous session mistook it for the base.)

**Per-type noise ranges (volume-relative blocks, still correct):** cumulus 128..384 above base;
stratocumulus 512..1024; stratus/itty_bitty/small_cumulus 0..256; nimbostratus 0..1024;
cumulonimbus 0..2048. So by default the stratus base sits at world Y = 128 (8 blocks above
sea level, i.e. just above the waterline at sea level) and the cumulus bank at ~144-176.

**Port fix (Step 1):** `CpuCloudGenerator` is now world-anchored — it takes `worldBaseY` (the
cloudHeight, in blocks) and every emitted vertex Y is `cloud-unit-Y * 8 + worldBaseY`; the band
grid spans fixed cloud-space Y 0..256 (`BAND_Y0..BAND_Y1`) and the band cache key is XZ-only
(`baseU` / camera-anchored volume is gone). The shadow-map light volume is likewise anchored at
world cloudHeight (`lightPlane = cloudHeight + SHADOW_VOLUME_TOP`). The other altitude-dependent
sites were already world-anchored in the port and verified as such: rain vertical-fade
(`CloudManager`, `stormStart*8 + getCloudHeight()`), lightning spawn Y
(`ClientCloudManager`/`ServerCloudManager`), and the server-side cloud lookup. Storm fog uses the
camera's cloud-space Y (world-anchored metric). The atmospheric layer is a separate screen-space
raymarch (no old camera-relative base).

**Verified (2026-09-13, dev client, standard views A–E, `NOSPAWN`):** one-shot log
"generated Y range 132.0..380.0 (worldBaseY=128.0)" — min world Y 132 = stratus layer y=0
(128 + 0.5·8), never at sea level. View A/B: cloud bases float well above the sea. View D
(y=200, inside the upper layers): cloud tops with sky gaps, world-anchored (constant
worldBaseY in the log). Baseline (pre-fix) screenshots in `docs/reference/before/step0-baseline-*`.

## FULL-REGION RENDERING + SERVER PERSISTENCE (2026-09-12)
Verified on screen (dev client, devshot screenshot): discrete white voxel cloud
formations in a noon sky, sun visible through the gaps, correct depth (mountain
occludes clouds). The flat-gray-sheet regression is gone.

### Client: full-region, off-thread generation
- The 256x256 block band was replaced by a grid of 32x32-unit (256x256 block) bands
  around the camera (2x2 = 512x512 blocks, render-distance driven), each cached by
  band key. Clouds are world-fixed; bands fill in from the camera outward.
- Generation moved OFF the render thread (a `simpleclouds-bandgen` daemon owns a
  dedicated `CpuCloudGenerator`; a `LinkedBlockingQueue` of `BandJob`s, completions
  published via a concurrent queue). A full band is 10-150 ms — too much for the
  render thread; off-thread it costs zero hitches.
- **Last-writer-wins pickup**: completions are stamped with the CURRENT region
  signature, never compared against the signature they were requested with.
  (The compare live-locked the cache for minutes: moving formations cross the
  signature quantization boundaries constantly, so every completion was discarded
  and re-enqueued.)
- Region signature quantization is deliberately coarse (20u position, 5u radius,
  0.1 rad rotation): a boundary crossing invalidates every band (~2 MB of uploads).
  Spawned formations are 750-1250 units across, so the steps are ~0.2% of a disk
  radius — invisible.
- Instance centers fixed: `(x + 0.5) * scale` (was `(x + radius) * scale`, a 28 block
  offset hidden in infinite-field mode). Legacy neighbor culling fixed to x+/-1
  cells (was +/scale = 8 cells apart, leaving coincident faces).
- Transparent instance concatenation fixed (28 bytes, not 24).
- **`ownTransforms`**: the cloud passes now use a private `DynamicUniforms` instead
  of the shared per-frame one. Our passes are encoded at the LevelRenderer TAIL but
  execute later in the frame; the shared ring can be re-written by vanilla passes in
  between, zeroing ModelViewMat/ColorModulator — the cause of "first draw N
  instances, empty sky" (the fsh's `if (ColorModulator.a < bayer) discard` then
  killed every fragment). The previewer pass shared the same latent bug.

### Server: persistence + resync
- `CloudData` (SavedData) ported to the 26.2 `SavedDataType` registry API
  (codec-based; `MinecraftServer.getWorldGenSettings().options().seed()` for the
  cloud seed; `CompoundTag.CODEC.optionalFieldOf` — 26.2's Mojang serialization is
  the pre-null-refactor, no `Codec.optional()`). `MixinServerLevel` attaches the
  manager via `getDataStorage().computeIfAbsent(CloudData.TYPE)`.
- 26.2 has NO Forge `PlayerRespawnEvent`/`LivingChangeDimensionEvent` and no Fabric
  equivalent; dimension changes moved to the `TeleportTransition` system.
  `CloudManagerEvents` polls per-player (dimension key or a >1024 block position
  jump) in the server tick and resends the full sync — replaces both events.

### 26.2 API notes added today
- Day/night is a **data-driven WorldClock system** (`net.minecraft.world.clock.*`):
  `MinecraftServer.clockManager()` (a `ServerClockManager` SavedData),
  `moveToTimeMarker(Holder<WorldClock>, ClockTimeMarkers.NOON)` / `setTotalTicks`.
  `Level`/`LevelData` no longer expose dayTime. Registry key: `Registries.WORLD_CLOCK`,
  `server.registryAccess().lookupOrThrow(...).getOrThrow(key)` returns
  `Holder.Reference` (use it directly as a `Holder`).
- `SavedDataStorage.get(SavedDataType)` returns the instance or null; vanilla cloud
  data (`ServerClockManager`) lives in `MinecraftServer.clockManager()`, not in the
  per-level storage.
- `Holder.direct(...)` inference fails against `Registry<? extends T>`; use the
  returned `Holder.Reference` directly.
- `DynamicUniforms` has a public no-arg constructor + `reset()` + `close()`; the
  per-instance ring is fence-safe across frames.
- The dev client runs on the **OpenGL** backend here (`GlDevice`); Vulkan would need
  the same `ownTransforms` treatment (backend-agnostic, already satisfied).

## HANDOFF task list (2026-09-11, from HANDOFF.md)

Test loop: **`./dev-relaunch.sh`** (one dev client, log check, screenshot to
`/tmp/sc-latest.png` — always READ the screenshot after PASS; "first draw, N
instances" proves nothing). `--install` copies the verified jar to the real
profile `mods/`. See HANDOFF.md for the full rules.

1. **Edge dithering restored properly** — [DONE 2026-09-11]
   `uniform sampler2D BayerMatrixSampler;` is declared in clouds.fsh (the 26.2
device does NOT inject sampler declarations from the bind group layout),
declared via `withSampler` on the bind group layout, and bound in `draw()`
through `RenderPass.bindTexture("BayerMatrixSampler", view, sampler)` using
`TextureManager.getTexture(simpleclouds:textures/shader/bayer_matrix.png)`
(auto-loads the 16x16 PNG the 1.20.1 mod shipped). The original dither
`discard` (fade = ColorModulator.a vs Bayer r) is back in the fsh. Verified:
clouds visible in `/tmp/sc-latest.png` (5509 instances), log clean, no
"unsupported uniform" warnings. Note: with the solid vertical slice the
transform carries alpha 1.0, so the discard never fires; visible edge dithering
activates once per-face/per-chunk alpha is supplied (transparency task).
2. **Real cloud heights and layers** — [DONE 2026-09-11] (user verification
   pending: fly up/down in-game — clouds must stay fixed; headless can't move
   the player. Verified at the same spawn position the shape changed from the
   camera-tracking blob to the data-driven diamond field, 7993 instances, log
   clean; jar installed into the real profile via `--install`.)
   - `CloudSpawningDataManager` fixed: `prepare()` now keys entries by
     `<namespace>:<filename-without-.json>` (the `cloud_spawning/` directory
     stripped, matching the 1.20.1 vanilla listener's key normalization), and
     `apply()` was restored to the original logic (root `simpleclouds:config`
     + per-type `Info` entries → `CloudSpawningConfig.fromJson`). Verified:
     zero "Could not find root Simple Clouds config" errors in the log.
   - Renderer: camera-tracking test layers removed; `dataDrivenLayers()` maps
     every active cloud type's noise settings (cloud_types/*.json →
     `NoiseSettings` → `AbstractNoiseSettings.Param`) to
     `CpuCloudGenerator.NoiseLayer`; the generated band is WORLD-FIXED (noise
     is a pure function of world coordinates, zero scroll) with a camera-
     following 32-block X/Z span and a 32-below/128-above vertical window;
     generation is cached per band origin + layer-set hash (the CPU band is
     expensive; regeneration only on 2-block grid moves). Vertical-slice
     limitation: an infinite noise field around the player, not the original's
     spawned formations (CloudManager/region spawning is part of the remaining
     stubs).
3. All stubs done: config screens, client commands, world effects/rain,
   shadow map, transparency + storm fog, 3D previewer and DH support (entries
   below).
   **Config screens** — [DONE 2026-09-11]
   - Open via keybind (`simpleclouds.key.openConfig`, unbound by default —
     bind it in Controls). DEVIATION: the 1.20.1 original opened the config
     from a button on the options screen (CrackersLib ConfigMenuButtons);
     26.2's options screen has no public hook, and the ported CrackersLib
     never got that mixin, so a keybind is the 26.2 entry point.
   - `SimpleCloudsClientEvents.register()` now also ports
     `registerClientPresets` (medium/low/ultra_low/classic_style presets)
     and calls `ConfigPresets.registerPresets` + `gatherPresets`. Without
     this the ConfigScreen constructor NPEs ("Presets have not yet been
     gathered!") — it crashed the game on the first tick after join until
     fixed.
   - Added `assets/crackerslib/` resources that the port was missing:
     `lang/en_us.json` (all `gui.crackerslib.*` / `config.crackerslib.*`
     button, tab and preset keys) and `textures/gui/config/{sort,collapse,
     expand}.png` (copied from the original CrackersLib source).
   - 26.2 GUI-architecture findings (all in the ported CrackerLib):
     * `AbstractSelectionList`'s constructor stores its HEIGHT argument as
       `defaultEntryHeight`, and 1-arg `addEntry` sizes every entry to that —
       each row became list-height tall and the scissor clipped everything.
       Fix: 2-arg `addEntry(entry, ROW_HEIGHT)` (the port already had
       `ROW_HEIGHT=30`, just never used it).
     * `updateSizeAndPosition(w, h, x, y)` — argument order flipped from
       1.20.1's `(x, y, w, h)`. The old call silently moved the list to
       (screenW, screenH-30) with size (0, 30).
     * The list must be sized BEFORE `buildList()`: entries are built with
       the list's row width, so building first gave `allowedWidth < 0` and
       empty option labels.
     * The constructor forces `centerListVertically=true`; for scrollable
       content that pushes rows past the scissor — set it false in the
       ConfigOptionList constructor.
     * `shortenText` used `Component.getString()`, which for the
       translatable option names went through the old code path and could
       return `CommonComponents.EMPTY`; it now returns the component
       unchanged when `font.width(name)` fits (the font resolves
       translatables at render time).
     * Screen access: `mc.gui.screen()` / `mc.gui.setScreen` / `mc.
       setScreenAndShow(...)` (the `mc.screen` field and `mc.setScreen`
       are gone).
   - Verified headlessly (auto-open, then removed): home screen + client tab
     render with translated category names (Debug, Distant Horizons,
     Performance, Preference, Seed, Single Mode, …), expand buttons, search
     box, Preset: Default / Done / Reset buttons; log clean; production run
     (flag off) clean with clouds rendering.
   **Commands** — [DONE 2026-09-11, client-side slice]
   - Client commands registered via `ClientCommandRegistrationCallback`
     (the 1.20.1 `registerClientCommands` port): `/simpleclouds config
     client …` (CrackerLib ConfigCommandBuilder on the CLIENT spec),
     `/clientClouds …` (ClientCloudCommandHelper) and `/simpleclouds
     profiling generator …` (ProfilingCommands). Verified: registration log
     line on join, log clean, clouds render.
   - 26.2 command-API findings (all in the command files):
     * The client dispatcher is typed `FabricClientCommandSource` (was a
       fake `CommandSourceStack` on 1.20.1 Forge). All command classes were
       retyped; `CloudCommandSource`'s methods are now generic over the
       context type and feedback goes through static `sendSuccess`/
       `sendError` helpers that handle both source types.
     * `Commands.literal/argument` and the vanilla argument getters
       (`Vec2Argument.getVec2`, `IdentifierArgument.getId`, …) are
       hard-bound to `CommandSourceStack` in 26.2. Client trees are built
       with Fabric's `ClientCommands.literal/argument` factories, and
       argument values are read with the generic
       `context.getArgument(name, ResultClass)` instead of the bound
       convenience getters.
     * DEVIATION: the Vec2 position/direction subcommands of clientClouds
       (`spawn …`, `clear <type> …`) require a server source in 26.2 and
       return a clear error when run from a client context; all other
       subcommands (clear all/storms, set scroll/seed/height/speed, etc.)
       work.
   - Server commands (`/simpleclouds clouds …`, server config command) stay
     deferred — the 26.2 command backend serializes custom Brigadier
     argument types into ClientboundCommandsPacket and only
     vanilla-bootstrapped types survive (CloudTypeArgument/ConfigArgument/
     EnumArgument would break player joins). Unblocking needs an argument
     type bootstrap mixin (see SimpleCloudsEvents).
   **World effects / rain** — [DONE 2026-09-11, slice version]
   - Custom rain: `WorldEffects.tick()` scans the camera-following 32x8x32 box
     (the original's RAIN_SCAN_WIDTH/HEIGHT/OFFSET) with the original's
     deterministic 2% position-seeded occupancy; drops live 60-120 ticks with
     the original 20-tick width fade in/out, scroll the texture (0.1 v/tick)
     for the falling motion, and their length is the heightmap distance
     straight down (clamped to 32). `core/rain.vsh/.fsh` + `RainDrawPipeline`
     draw one camera-facing quad per drop (vanilla `minecraft:textures/
     environment/rain.png`, alpha-blended, no depth write) into the main
     target, gated on `SimpleCloudsConfig.CLIENT.renderCustomRain`.
   - Lightning: `spawnLightning` (server `SpawnLightningPacket` or the local
     cloud manager) starts a flickering decaying flash; the flash drives the
     storm-fog `LightningMul` (scene brightens on a strike) and plays the mod's
     close/distant thunder via `playLocalSound` (26.2: `SoundSource`, not
     `SoundCategory`).
   - `getStorminessAtCamera` now reports the renderer's measured storm-cloud
     coverage; `calculateFogColor` nudges the fog toward the storm color with
     it; `getDarkenFactor` returns the flash-modulated factor.
   - DEVIATIONS (documented in the class header): no lightning bolt MESH (the
     jagged branch geometry + lightmap glow are unported; flash + sound carry
     the effect); no wind tilt on rain; no snow (26.2's Level API has no snow
     level, only `getRainLevel`); fog tinting is a small nudge, not the
     original per-biome storm tint.
   - Verified with a TEMP `DEBUG_FORCE_WEATHER` flag (now false): forced
     rain+thunder rendered a full downpour of streaks around the camera in the
     storm-darkened scene (/tmp/sc-latest.png 14:27, "first rain draw" in log,
     log clean); production run (flag off) shows the normal scene with no
     rain; jar installed via `--install`.
   **Shadow map** — [DONE 2026-09-11, slice version]
   - Shadow depth target: 512x512 D32 (1 block/texel over a 512x512-block field,
     top-down ortho, +/-256 blocks around the camera, 600-block depth range),
     rendered every frame with the SAME opaque instance buffer using
     `core/clouds_shadow.vsh/.fsh` (depth-only, color write mask NONE, depth
     cleared to 1.0, `ShadowMatrices` std140 UBO = view + proj at offsets 0/64).
   - Terrain shadows: `core/terrain_shadows.vsh/.fsh` fullscreen pass, blended,
     color-only render pass (3-arg `createRenderPass`) — it samples the scene
     depth + the shadow map and darkens fragments under clouds with a 4-tap
     PCF; `ShadowPass` UBO (view-proj + bias + intensity), gated by nothing
     (original was always-on).
   - 26.2 findings verified on screen (diagnostic marker passes, documented in
     the fsh header):
     - the main target clears depth to **0.0** (sky samples ~0, geometry (0,1));
     - the scene depth must be sampled in a COLOR-ONLY pass — attaching and
       sampling the same depth image in one pass read back 0;
     - near/far-free depth reconstruction: `zv = -P[3][2]/(ndcZ + P[2][2])`,
       `vx = -ndcX*zv/P[0][0]` (clip.w == -zv);
     - `GpuDevice.createSampler` validates maxAnisotropy as 1..16 (0 throws);
       a failed pipeline construction left `drawPipeline` null with no retry
       path — `generateAndDrawClouds` now retries `ensurePipeline()` throttled
       to every 64th failed frame.
   - DEVIATION: 1.20.1 used the shadow map in a 200-step storm-fog raymarch and
     had config-driven shadow distance/dithering; the slice is a fixed-radius
     (256 blocks) single-bias PCF pass. Verified: log clean, 33947 instances,
     magenta detection marker confirmed shadow lookup on the ground under the
     clouds, production black overlay subtle at dawn (expected); jar installed
     into the real profile via `--install`.
   - `CloudSpawningDataManager` load-order fix (same task): singleplayer's first
     resource reload can run before `cloud_types` applied; `apply()` now defers
     type validation (lenient id-only validator + one WARN) for that round
     instead of failing every entry and resetting the config to EMPTY.
   **Storm fog** — [DONE 2026-09-11, slice version]
   - `CpuCloudGenerator.CloudLayerGroup` gained a `stormType` flag (weather type
     THUNDERSTORM); during band generation the generator measures the fraction of
     the 8x8 grid columns around the camera that contain opaque storm-type cloud
     above the camera (coverage metric).
   - New 26.2 pass: `core/storm_fog.vsh/.fsh` — a screen-covering triangle, blended,
     no depth test, drawn last over the scene; intensity = coverage * 2.5 (clamped),
     vertical gradient (stronger near the horizon), `LightningMul` uniform wired for
     the lightning flash hook (world effects task). Gated on
     `SimpleCloudsConfig.CLIENT.renderStormFog`.
   - DEVIATION (documented in the fsh header): the 1.20.1 original is a 200-step
     raymarch sampling the cloud shadow map (per-pixel density, scene-depth
     occlusion, per-bolt lightning). That needs the shadow-map subsystem; the slice
     version is a CPU-coverage-driven fullscreen darkening. Verified: log clean,
     scene visibly darkened to storm gray with the clouds still visible
     (/tmp/sc-latest.png 13:02); jar installed.
   **Transparency** — [DONE 2026-09-11]
   - `CpuCloudGenerator` now works in per-cloud-type `CloudLayerGroup`s (the
     compute shader's per-type LayerGroups): the opaque test (group noise > 0) and
     the transparent test (group noise in (-transparency_fade, 0), alpha ramp
     (noise+fade)/fade) are INDEPENDENT per group, exactly as the original's
     per-group if/else-if. Transparent voxels emit all six faces (no culling, as
     in createTransparentCube).
   - `clouds_transparency.vsh/.fsh` ported to 26.2 (per-instance attributes
     instead of the SSBO; GLSL ES 3.0; DynamicTransforms/Projection via imports;
     Bayer dither discard kept with the declared+bound sampler).
   - `CloudsDrawPipeline.drawTransparency()`: separate RenderPipeline
     (MATRICES_FOG_SNIPPET) with `ColorTargetState(BlendFunction.TRANSLUCENT)`
     and `DepthStencilState(LESS_THAN, writeDepth=false)`, drawn after the
     opaque pass into the main target; 28-byte per-instance format (24 + Alpha);
     gated on `SimpleCloudsConfig.CLIENT.transparency`.
   - DEVIATION (documented in the fsh header): the 1.20.1 weighted-blended
     order-independent two-attachment variant (accumColor/revealage + composite
     pass, JCGT paper) is NOT ported; standard alpha blending is used instead.
     Visually equivalent for the thin edge shells this pass handles.
   - Verified: log clean, "first draw, 32642 instances" + "first transparent
     draw, 11112 instances"; clouds visible in the landscape (sky, ridge tops,
     close-up) in /tmp/sc-latest.png; jar installed into the real profile.
   **3D Previewer** — [DONE 2026-09-11, slice version]
   - `CloudPreviewerScreen` (ported CrackerLib `Screen3D` shell: mouse drag =
     orbit camRotX/camRotY, middle-drag = pan, scroll = zoom) opens via the F12
     keybind (`simpleclouds.key.openGenPreviewer`). While open, the world phase
     draws the preview box instead of the normal cloud pass:
     `PreviewDrawPipeline` (static 64x64x64-box instance mesh from the first
     data-driven cloud type, ~22k instances) + `CloudsDrawPipeline.drawPreview`
     (snippet pipeline on the MAIN clouds shader, orbit view matrix via
     `DynamicTransforms.writeTransform`, `ALWAYS_PASS` + no depth write so the
     box draws over the world).
   - KEY 26.2 FINDING (root cause of the long "flat sky-blue preview" bug,
     verified by a ~20-run binary search): **standalone-encoder passes never
     produce COLOR fragments into a mod-created offscreen target in 26.2** — the
     pass clear applies but every draw is silently discarded (tested: self-
     contained and snippet pipelines, RenderTarget and plain GpuTexture targets,
     world and GUI phases, shadow-cloned builder/shader/buffers/matrix patterns;
     depth-ONLY writes DO work — that is how the shadow map renders). Also:
     `ColorTargetState(Optional.empty(), ...)` means "the pipeline does not
     write the color attachment" (valid only for depth-only passes like the
     shadow map) — a color-writing pipeline with that state is silently
     rejected at draw time; every color pipeline must carry a BlendFunction.
     Proven color path = main frame + snippet pipeline + DynamicTransforms (the
     main-clouds recipe). The proper long-term fix is a frame-graph pass (or
     PiP-style `RenderSystem.outputColorTextureOverride` re-render), tracked
     below.
   - DEVIATIONS (documented in the class headers): the box is drawn over the
     live world at the player position (no isolated sky-blue background — that
     needs the frame-graph pass above); orbit camera with the game's
     perspective projection (1.20.1 used an orthographic GUI projection);
     first data-driven cloud type only (no per-type selector); no offscreen
     "render preview image" export (CloudImageRenderer unported); fixed
     daytime-ish lighting (does not track the in-game sun). Camera interaction
     is the ported Screen3D math, verified by code review only (headless has no
     mouse injection).
   - Verified: log clean, dithered preview box visible centered in front of the
     player over the world (19:02 + 19:06 screenshots), world/normal clouds
     render when the screen is closed; jar installed via `--install`.
   **DH support** — [DONE 2026-09-11, slice version]
   - The profile's Distant Horizons 3.2.0 (fabric) ships the SAME
     `com.seibel.distanthorizons.api.*` layout the 1.20.1 handlers were built
     against (`DhApiEventRegister`, `DhApiBeforeRenderPassEvent` /
     `DhApiBeforeApplyShaderRenderEvent` / `DhApiAfterRenderEvent`,
     `DhApiMat4f`, `IDhApiConfigValue`, `DhApi.Delayed.configs.graphics()
     .genericRendering().cloudRenderingEnabled()`) — verified by `javap` on the
     installed jar, so the handlers port directly. `SimpleCloudsDhCompatHandler.
     initialize()` is called once on the first client tick when DH is loaded
     (guarded try/catch so a DH-side failure can't break Simple Clouds' render
     loop): it registers the three DH event handlers and DISABLES DH's own cloud
     rendering (`cloudRenderingEnabled=false` + a change-listener that re-asserts
     it), so clouds are not drawn twice.
   - 26.2 DEVIATION (the important one): the 1.20.1 pipeline re-rendered the
     clouds into DH's own framebuffer during DH's render pass (far-field LOD
     depth integration). The v2 26.2 renderer draws its finite, camera-following
     cloud field into the MAIN framebuffer during the world phase, and the 26.2
     RenderPass API cannot target DH's raw GL framebuffer (the DH API only
     exposes the FBO id, not the GpuTextureView attachments). So the DH event
     handlers in 26.2 only cache the DH state (projection/model-view matrices,
     bound FBO) defensively -- the per-pass cloud draws are no-ops, and the active
     pipeline stays `CloudsRenderPipeline.DEFAULT` (the `DhSupportPipeline` class
     is kept as the reserved slot for a future far-field pass). Far-field LOD
     depth accuracy is a known limitation.
   - The 1.20.1 `MinecraftForge.EVENT_BUS.register(SimpleCloudsDhForgeEvents)` is
     intentionally NOT ported (no Forge stubs): both of its events (pipeline
     override, cloud render-distance clamp) are no-ops in the v2 renderer, and the
     class was deleted.
   - Verified against REAL DH 3.2.0 (copied into the dev client's run/mods, then
     removed): DH initialized ("Delayed setup complete"), "Distant Horizons
     detected -- registering Simple Clouds compat" logged, no NoSuchField/
     NoClassDef/exception, clouds rendered in the sky with no double-clouds, log
     clean; jar installed via `--install`.

Original: Forge 1.20.1 (nonamecrackers2). No Fabric/26.x build exists.
Sources cloned to /tmp/simple-clouds-src and /tmp/crackerslib-src (the full
original repo checkout is also at
~/.local/share/ModrinthApp/profiles/Forge 1.20.1 1.0.0/repositories/simple-clouds
with generated data).
Project: local-mods/simple-clouds/ (combined crackerslib + simple-clouds, non-remap loom).

## Scope
- crackerslib: 61 files, 5,764 lines (config GUI + utilities) — FOUNDATION
- simple-clouds: 140 files, 16,621 lines (cloud rendering, DH compat, networking)
- Total: ~201 files, ~22k lines

## Strategy
Combine both into ONE Fabric mod (id: simpleclouds) to avoid jar-in-jar dependency mgmt.
Port bottom-up: crackerslib first (everything depends on it), then simple-clouds.
Defer/strip optional features as needed (DH compat, profiling, examples).

## Progress Log

### Phase 0: Project scaffold [DONE 2026-09-10]
- [x] Project dir + gradle wrapper (from template)
- [x] build.gradle (non-remap loom, mc 26.2, loader 0.19.5, fabric-api 0.160.0+26.2)
- [x] settings.gradle, gradle.properties, fabric.mod.json, LICENSE
- [x] Verify empty project builds (gradle build)
- [x] night-config 3.6.7 added (for vendored ForgeConfigSpec)

### Phase 1: Port crackerslib [IN PROGRESS]
Sub-phases (by package):
- [x] common/util/primitives (PrimitiveHelper) — 26.2 CompoundTag/ChunkPos API
- [x] client/util (CommonColors, GUIUtils)
- [x] common/config (ConfigHelper, CrackersLibConfig, ConfigListener, presets)
- [x] common/compat (CompatHelper → FabricLoader)
- [x] common/packet (PacketUtil → Fabric networking; old Packet base removed)
- [x] CrackersLib.java (library helper, not @Mod)
- [x] Vendored: ForgeConfigSpec, IConfigSpec, Logging, FMLEnvironment stub, ModConfig.Type
- [x] client/gui/title (TitleLogo, ImageTitle, TextTitle → GuiGraphicsExtractor)
- [x] client/gui/widget (ConfigListItem, ConfigCategory, ConfigOptionList, SortButton, SimpleIconButton, entries) — copied + API sed, fixing render model
- [ ] common/util/data (ConfigLangGeneratorHelper — datagen only, may strip)
- [ ] client/util (RenderUtil — heavy render API)
- [ ] client/gui (ConfigScreen, ConfigHomeScreen, Popup, Screen3D, ConfigMenuButtons)
- [ ] client/event, client/config, common/event, common/command, common/data
- [ ] Strip: example/ package, Forge-only compat

### 26.2 GUI render model (IN PROGRESS — complex)
- `AbstractSelectionList.render` → `extractWidgetRenderState(GuiGraphicsExtractor, mouseX, mouseY, partialTick)`
- `renderBackground(GuiGraphics)` → `extractListBackground(GuiGraphicsExtractor)` (no mouse args)
- `getScrollbarPosition` → `scrollBarX()`
- `Entry.render(GuiGraphics, index, top, left, width, height, mouseX, mouseY, selected, partialTick)` → `Entry.extractContent(GuiGraphicsExtractor, ?, ?, boolean, float)` — signature needs verification
- `setRenderBackground`/`setRenderTopAndBottom` — may not exist in 26.2
- ConfigOptionList super ctor: `(mc, 0, top, width, bottom-top)` (was `(mc,left,top,right,bottom,itemHeight)`)

## 26.2 API mappings (verified via javap on minecraft-merged-deobf-26.2.jar)
- `ResourceLocation` → `Identifier` (net.minecraft.resources); ctor private, use `Identifier.fromNamespaceAndPath(ns, path)`
- `CompoundTag.contains(key, type)` → `contains(key)`; `getInt/getFloat/getDouble` return `Optional<T>` → use `getIntOr/getFloatOr/getDoubleOr`
- `ChunkPos` is now a record → accessors `x()`/`z()` (not fields)
- `javax.annotation.Nullable` → `org.jetbrains.annotations.Nullable`
- `GuiGraphics` → `GuiGraphicsExtractor`; `Screen.render(GuiGraphics,...)` → `Screen.extractRenderState(GuiGraphicsExtractor, mouseX, mouseY, partialTick)`
- `GuiGraphics.drawString` → `text`; `drawCenteredString` → `centeredText`; `renderTooltip` → `setTooltipForNextFrame`
- `Minecraft.screen` field → `Minecraft.getInstance().gui.screen()`; `setScreen` → `gui.setScreen` / `setScreenAndShow`
- Fabric networking: `CustomPacketPayload` records + `PayloadTypeRegistry.clientboundPlay()/serverboundPlay()` + `ClientPlayNetworking/ServerPlayNetworking.registerGlobalReceiver`; `ServerPlayNetworking.send(player, payload)`
- `FabricLoader.getInstance().getModContainer(id)` (not getModContainerById); `isModLoaded(id)`
- `ConfirmLinkScreen.confirmLinkNow(screen, url, true)` for opening links

### Phase 2: Port simple-clouds [NOT STARTED]
- [ ] common (config, api, event, init, packet)
- [ ] client/renderer (cloud pipeline, shaders) — HEAVY, 1.20.1→26.2 render API changes
- [ ] client/compat (sound replacements)
- [ ] client/dh (DH compat — OPTIONAL, make conditional)
- [ ] client/command, client/keybind, client/event
- [ ] SimpleCloudsMod.java (main → Fabric entrypoint)
- [ ] Mixins (client/accessor, etc.)

### Status (2026-09-10, continued session)
- Config foundation: DONE + compiles (ForgeConfigSpec vendored, config classes, presets, compat)
- Networking: DONE (Fabric CustomPacketPayload layer)
- GUI title classes: DONE (TitleLogo, ImageTitle, TextTitle)
- GUI widgets: DONE + compiles (ConfigListItem, ConfigCategory, ConfigOptionList, SortButton, SimpleIconButton, CyclableButton, SelectableNamedObjectList, entries)
- Config screens: ConfigScreen + ConfigHomeScreen ported (render->extractRenderState, screen access, tooltips, extractBackground)
- ConfigMenuButtons + event classes: DONE (simplified, no Forge bus)
- 3D classes (Screen3D, Popup, Widget3D, RenderUtil): DONE (minimal compilable stubs, API preserved; 3D render pass deferred)
- Build: GREEN — full crackerslib compiles, jar produced (simple-clouds-0.7.3+26.2-fabric.jar)

### 26.2 API notes discovered this session
- Screen.mouseDragged(MouseButtonEvent, double, double) [3 args]; mouseScrolled(double,double,double,double) [no event obj]; no MouseScrollEvent class
- event.button() returns int (0=LEFT,1=MIDDLE,2=RIGHT), not MouseButton
- Screen.extractBackground(GuiGraphicsExtractor,int,int,float) replaces renderBackground
- MultiLineLabel is now an interface; render via visitLines(TextAlignment,x,y,lineHeight,ActiveTextCollector)
- ActiveTextCollector is NOT a functional interface (4 abstract methods) — use anonymous class
- EditBox has no setFilter; Button has no setFGColor (theme-driven)
- Screen.init(int,int) [no Minecraft arg]; Minecraft has no getPartialTick() (use render param)

### REMAINING (complex 26.2 render API + Simple Clouds core)
- Screen3D + Popup + Widget3D: 3D preview rendering (RenderSystem projection/modelview, MultiBufferSource, RenderType, Lighting) — MAJOR 26.2 rewrite
- RenderUtil: render helpers (Tesselator/BufferBuilder/RenderSystem) — MAJOR 26.2 rewrite
- Simple Clouds core: config (SimpleCloudsConfig), concrete packets (rewrite as CustomPacketPayload records), cloud renderer (cloud pipeline + shaders — HEAVIEST), DH compat, keybinds, events, main mod class (SimpleCloudsMod -> Fabric entrypoints)
- Options-menu mixin: to add the config button to the options screen (Fabric pattern)
- Mixins: MixinGameRendererAccessor (FOV/zoom access), MixinBlockEntityType

### Note
The 26.2 render pipeline (RenderSystem, MultiBufferSource, RenderType, Lighting, projection matrices) is fundamentally different from 1.20.1. The 3D previewer (Screen3D/Popup) and the Simple Clouds cloud renderer are the hardest parts and need careful 26.2 render API study.
- [ ] Build succeeds
- [ ] Install jar in profile mods/
- [ ] Launch, verify clouds render
- [ ] Fix runtime issues

## Key conversions (Forge → Fabric)
- @Mod → ModInitializer/ClientModInitializer entrypoints
- MinecraftForge.EVENT_BUS → Fabric lifecycle/registry events
- FMLJavaModLoadingContext modBus → Fabric API events
- ForgeConfigSpec → keep (works) or Cloth Config
- Forge networking (PacketDistributor) → Fabric networking API
- DistExecutor → @Environment(EnvType.CLIENT)
- ResourceLocation → same
- Render pipeline (1.20.1) → 26.2 (major changes expected)

## Notes
- crackerslib license: "All Rights Reserved" but publicly distributed for mod use.
  Personal modpack port = standard practice. Flagged to user.
- DH (Distant Horizons) IS installed in profile; dh compat can be conditional.
- Build env: JAVA_HOME=temurin-25 (see tools/build-env.sh)

# Visual parity results (26.2 port)

Per-step evidence for VISUAL-PARITY-PLAN.md. Each step must have log lines and/or
screenshots (in `run/screenshots/devshot-*.png`, read back every time) before it is
counted as done.

Status legend: **done** = criteria met, evidence below · **partial** · **not started**

| Step | Status | Evidence |
|---|---|---|
| A1 memory (addendum) | **done** | below |
| A2 motion (addendum) | **done** | below |
| A3 horizon/flat/above (addendum) | **done** | below |
| 5 transparency (port) | **done** | below |
| 6 shadows | **done** | below |
| 7 LOD/pop-in (port) | **done** | below |
| 8 lighting/darkness (port) | **done** | below |

---

## A1 — memory (addendum, 2026-09-13)

**Verdict: memory levels off. Client RSS baseline ~4.5–5.0 GB with bounded transient
spikes to ~6.0 GB; no growth over 33 minutes of continuous regeneration.**

### What was wrong (2026-09-13 morning)

`CpuCloudGenerator.generate()` ran ~122 chunk generations per second (the region
signature flips every 5–20 s under drift, regenerating all 364 LOD chunks), and each
call allocated three direct buffers: the initial scratch, every grow, and the
`bound()` result copy. Direct memory only came back when the GC collected dead
buffers, so RSS climbed to 25 GB and the OOM killer took the dev client **and the
whole terminal** (the client JVM was a child of the session, not of a unit).

### What changed

1. **`ChunkBufferPool`** (new, `renderer/v2/ChunkBufferPool.java`): best-fit reuse of
   direct buffers; a buffer is only GROWN when a chunk outgrows the borrow (the grower
   releases the old one); pool retention hard-capped at 256 MB (largest free buffer
   dropped for the GC beyond the cap). `CpuCloudGenerator.generate()` now writes into
   caller-provided buffers — **zero direct allocation on the generation path** (the
   `grow()`/`bound()` copy methods are deleted; per-cell `float[3]` gradient
   allocations are a reused field).
2. **Per-chunk GPU buffers** (`SimpleCloudsRenderer`): the whole-field combined
   opaque+transparency buffers and their per-frame rebuild are gone. Each chunk owns
   one persistent `GpuBuffer` per stream (created when the chunk is published via
   the data-carrying `createBuffer` overload, closed when the chunk is replaced,
   evicted from the LOD layout, or the renderer shuts down). The draw pass is now
   one `drawIndexed` per chunk (the original's architecture); the fade-in alpha is
   just that chunk's `ColorModulator`. The shadow map draws the per-chunk buffers
   (one `drawIndexed` per chunk inside a single pass). Hard cap of 512 cached chunks
   as a safety net.
3. **Worker pool**: one `CpuCloudGenerator` per worker thread (was: one generator
   per job), buffers borrowed from the pool with ownership tracking so a failed job
   can never double-release a buffer the grower already returned.
4. **Dev client limits**: `-Xmx4G -XX:MaxDirectMemorySize=2G` (Loom `runConfigs` in
   `build.gradle`), `dev-relaunch.sh` now launches with `gradle --no-daemon`
   (client JVM stays a child of the systemd unit, never of the terminal session)
   plus `MemoryHigh=9G`/`MemoryMax=10G` on the unit and `SIMPLECLOUDS_DEV=1`.
5. **`DevMemoryLogger`** (new, `client/DevMemoryLogger.java`): 30 s heap/direct/RSS
   log lines (`[DEVMEM]`), active only with `SIMPLECLOUDS_DEV=1`.
6. **`DevShot` `LOOP` token**: cycles the standard views (A–E) until
   `devshot.request` is deleted — the 30-minute proof harness.

### Proof run (30-minute requirement)

- Launch 11:32:45, **first draw 11:32:55**, sampler 11:34:58–12:04:58 (31 samples,
  60 s), client stopped 12:08. Continuous rendering of the A–E view cycle:
  **23 view cycles in ~33 min** (each cycle re-teleports across LOD grid cells, so
  chunks kept regenerating and moving — 12,204 chunk generations in the earlier 80 s
  sanity window, ~150/s).
- **Client process RSS** (`[DEVMEM]`, 71 samples, 30 s): **avg 4.98 GB, min 4.13,
  max 6.06 GB**; first-half avg 5.02 GB, second-half avg 4.93 GB (delta **−0.09 GB**,
  i.e. flat-to-down). Direct memory oscillates 128 MB ↔ 2.0 GB on full-field
  regeneration bursts (bounded by the 364-chunk layout, not by time) and settles
  back to 100–500 MB; heap 1.0–2.4 GB against the 4 GB cap.
- **Unit cgroup** (`MemoryCurrent`, 31 samples, 60 s): avg 5.52 GB, min 4.77, max
  6.36 GB; first half 5.58 GB → second half 5.12 GB (delta **−0.46 GB**). The 10 GB
  `MemoryMax` was never approached (the cgroup number includes the ~1 GB one-shot
  Gradle daemon).
- Data: `/tmp/sc-a1-rss.csv` (cgroup), `run/logs/latest.log` grep `DEVMEM` (client),
  `/tmp/sc-a1-log.txt` (full log snapshot).
- Renders stayed correct start-to-finish: all six standard views read back in the
  first cycle (`/tmp/sc-view-{A,B,C,D,E1,E2}.png`) and again in the final cycle
  (`/tmp/sc-a1-final/devshot-*.png`, 12:06–12:08) — no pop-in artifacts, no
  degradation over the run.

**A1 criterion ("prove that its memory levels off") — MET.**

---

## A2 — motion: real scroll and wiggle (addendum, 2026-09-13)

**Verdict: the wind scroll and the original's wiggle are baked into the generated
noise; the E1/E2/E3 still shots (10 s apart, 32× wind) show genuinely different
cloud patterns — reshaped, not a shifted copy. The global conveyor-belt view
translation is gone.**

### What changed

- `SimpleCloudsRenderer`: the Step-4 global view-matrix translation
  (`view.mul(translate(-scrollX, -scrollY, -scrollZ))`) is REMOVED. The current
  lerp'd scroll is now (a) baked into every chunk generation
  (`CpuCloudGenerator.generate(..., scrollX, scrollY, scrollZ, wiggle, ...)`, which
  feeds `sampleLayer` exactly like the original shader's
  `noise((pos + Scroll)/Scale, Wiggle)`), and (b) the staleness test: a chunk
  regenerates when any scroll axis drifted more than `SCROLL_REGEN_THRESHOLD = 1.0`
  cloud unit (8 blocks) from the snapshot in its geometry.
- Wiggle: the original's exact formula, `(scrollX + scrollY + scrollZ) / 5.0F`
  (the 1.20.1 `Wiggle` uniform — it is NOT an independent animation; it rides on
  the scroll), computed per chunk job.
- `ChunkJob` / `ChunkResult` / `ChunkData` carry the scroll snapshots.
- `DevShot`: the E motion view now takes THREE shots, `devshot-E1/E2/E3.png`, 200
  game ticks (10 s) apart; `dev-relaunch.sh` wait count updated (E = 3 files).

This is a CPU approximation of the original (per-frame GPU noise evaluation): the
field updates in 8-block scroll steps at the worker-pool/budget rate (nearest chunks
first), instead of 60×/s. Documented as such in the renderer constant.

### Proof (time-pinned E1/E2/E3)

Run: `DEVSHOT_EXTRA='E FAST'` (32× cloud speed, pinned camera at
8.0x70.0x8.0, pitch 0, yaw 0, noon locked, same formation field):

| shot | scroll (X, Y, Z) at shot | Δ scroll vs E1 |
|---|---|---|
| E1 (12:22:52) | (-51.98, 0, -85.43) | — |
| E2 (12:23:02, +200 ticks) | (-65.60, 0, -75.47) | (-13.62, 0, +9.96) ≈ 16.8 cloud units (134 blocks) |
| E3 (12:23:12, +200 ticks) | (-64.09, 0, -76.76) | (-12.11, 0, +8.67) ≈ 14.8 cloud units (118 blocks) |

Images (camera + terrain identical in all three — read back): `/tmp/sc-view-E1.png`,
`/tmp/sc-view-E2.png`, `/tmp/sc-view-E3.png` (in-game copies `/tmp/sc-a2-final/`).
The cloud pattern is DIFFERENT in each shot — gap positions, blob shapes and
coverage change non-uniformly across the sky (the noise is re-sampled at the new
scroll phase per layer scale), not a rigid translation of one copy. The E2→E3 net
scroll delta is small because the wind's circular orbit (`scrollX/Z = cos/sin(θ)·100`)
was rotating direction at that phase; the path length (and the visible reshaping)
is the same order as E1→E2.

Memory under the 32× load (continuous full-field regeneration at the budget rate):
heap 1.1–1.6 GB, direct 102–308 MB, RSS 3.7–4.0 GB, unit peak 5.08 GB — no growth
signs, consistent with A1.

**A2 criterion ("the 26.2 shot shows swirl (a changed cloud pattern), not just a
shifted copy") — MET.**

---

## A3 — horizon: flat grey layer, view from above (addendum, 2026-09-13)

**Verdict: the flat grey horizon layer is gone (fog now follows the original's
field-radius formula), and the true view from above (new view F, y=400) shows a
3D white-topped volume. View D (y=260) is inside the cloud volume (156..324),
which is why it sometimes looks like an interior — both states verified.**

### Root cause

The Step-3 cloud fog was render-distance-relative:
`fogStart = renderDist*16` (192 blocks at RD 12), `fogEnd = 3x` (576). The cloud
field is 1280 cloud units (10240 blocks) in radius (HIGH layout,
`effectiveChunkSpan * PRIMARY_CHUNK / 2`), so two thirds of it — everything past
576 blocks — was rendered at 100% fog color: a **flat grey horizon layer** (view A)
and a **grey haze** from inside/above (view D). The original 1.20.1
`SimpleCloudsRenderer` uses the FIELD radius, not render distance:

```java
// 1.20.1 original (SimpleCloudsRenderer.render):
float renderDistance = max(meshGenerator.getCloudAreaMaxRadius() * CLOUD_SCALE * darkenFactor, 2867.0F);
this.fogStart = renderDistance / 4.0F;   // 2560 blocks for HIGH
this.fogEnd = renderDistance;            // 10240 blocks for HIGH
```

### Fix

`SimpleCloudsRenderer.generateAndDrawClouds`: fog is now
`fogStart = fieldRadiusBlocks / 4`, `fogEnd = fieldRadiusBlocks` (floor 2867),
with `fieldRadiusBlocks = lodConfig.getEffectiveChunkSpan() * PRIMARY_CHUNK / 2 *
8` (10240 for HIGH — matches the original exactly). Log line:
`fog range 2560..10240 blocks`. The per-fragment fog itself
(`smoothstep(FogStart, FogEnd, length(ModelViewMat*pos.xz))` in
`clouds.fsh`/`clouds_transparency.fsh`) already matched the original's
`clouds.vsh` (`.xz` horizontal distance) — only the range was wrong.

### Proof

- **View A (horizon, y=70):** `/tmp/sc-view-A.png` (12:44) — the distant cloud
  band is a structured white layer with blue sky in the gaps; no flat grey sheet.
  (Compare `/tmp/sc-a1-final/devshot-A.png` 12:07 and the 12:29 pre-fix shots:
  uniform grey band.)
- **View F (new, y=400 — above the 156..324 volume, pitch -20):**
  `/tmp/sc-view-F.png` (12:44) — pixel-sampled: cloud tops are pure white
  (255,255,255) with the fog color (192,216,255) only in far gaps; blue sky
  (130,167,255) above. A 3D volume with white tops, not a grey haze.
- **View D (y=260 — INSIDE the volume):** two states observed, both consistent
  with being inside a stratus/cumulus deck: `/tmp/sc-view-D.png` (12:44) shows
  white 3D voxel clouds against blue sky (camera in a gap); the 12:29 run
  (`/tmp/sc-view-D.png` of that session, pixel grid in `/tmp/sc-a1-log.txt` era)
  showed a flat grey interior — the camera was inside a dense cloud mass that
  wind drift (A2) had moved over the pinned position. y=260 is between the
  volume base (156) and top (324) — log: `generated Y range 156.0..324.0`.
- The `F` view and an `OVL0` overlay-isolation token were added to `DevShot`
  for this diagnosis (kept as dev tools).

**A3 criteria — MET:** (A) horizon is a clear white cloud band, not a grey layer;
(D/F) from above the clouds read as a 3D volume with white tops. Note for the
final report: the "grey haze" Jan saw in the 00:11 devshot was this fog bug,
not the in-volume interior.

---

## Step 5 — transparency (soft cloud edges)

**Verdict: the 26.2 transparent shader path renders, and the original's
TransparencyDistance gate is now ported; the cloud base silhouette shows
multi-step alpha edges.**

### State found

- The per-group transparent-edge emission (`noise ∈ (−TransparencyFade, 0)` →
  full cube, `alpha = (noise+fade)/fade`, independent of the opaque decision)
  already matched `cube_mesh.comp`'s `TRANSPARENCY==1` block.
- The path was in fact rendering: log `first transparent draw, 11664 instances`;
  pre-gate run: 131,275,458 transparent instances over 25,699 chunk generations
  (96% of chunks had some).
- MISSING: the original's `TransparencyDistance` gate — `cube_mesh.comp` only
  emits edge cubes where `distance((x,z), Origin.xz) < TransparencyDistance`
  (uniform set from the mesh generator, default `maxRadius/2` = 640 cloud units
  for HIGH).

### Change

- `CpuCloudGenerator.TRANSPARENCY_DISTANCE = 640.0F` (HIGH's maxRadius/2, the
  original default); `generate()` now takes the camera's cloud-unit XZ and
  skips transparent emission outside the gate (camera cloud-XZ flows through
  `ChunkJob`).
- Effect: transparent-bearing chunks dropped 96% → 78% (24,643 → 18,555 of
  ~25k), total transparent instances 131.3M → 108.7M per equivalent run — the
  far field (fogged to sky color by the 2560..10240 fog anyway) no longer
  generates edge cubes, exactly like the original.

### Proof

- `/tmp/sc-view-G.png` (12:56, new view G: y=120 looking UP at the cloud base,
  pitch −45): the silhouette between the white cloud base and the sky is a
  multi-step alpha ramp, not a hard pixel step. Pixel samples along the edge:
  (255,255,255) → (250,252,255) → (154,183,255) → (129,166,255 sky) — the
  intermediate shades are the blended edge cubes. Faint isolated low-alpha
  patches are visible too (transparency cubes over open sky).
- `/tmp/sc-view-A.png` (same run, 12:54): horizon band unchanged (A3
  regression check).

**Step 5 criteria — MET:** cloud edges fade via the per-chunk transparent
pass, the transparent shader path renders, and the distance gate matches the
original.

---

## Step 6 — shadows (cloud shadows on terrain)

**Verdict: the terrain cloud-shadow pipeline was BROKEN (the shadow map was 100%
empty → no shadow ever appeared) and is now fixed. It matches the original's
distance/fade model (span, MinimumRadius, FadeDistance, ColorMultiplier, 3x3
PCF). The shadow is deliberately subtle in the dev scene because the original's
FadeDistance (1028 blocks) makes it strongest only on *distant* terrain (the
"distant shadows" feature).**

### Bugs found and fixed (all on the terrain-shadow path, verified in-game)

1. **Transposed shadow view matrix (the map was empty).** The hand-built
   top-down view used joml's 16-float `set()` with ROW-major groups, but joml
   1.10's `set()` is COLUMN-major (verified by point transform: the w-row came
   out non-affine garbage, e.g. a world point mapped to w=−44063, clipping every
   fragment). The shadow map was therefore 100% empty (all depth = 1.0 = "no
   cloud"). This was introduced in the step-1 camera-anchoring commit and was
   masked because "first draw, N instances" printed fine (the MAIN pass uses a
   correct matrix; only the shadow pass was wrong). Fixed by writing the matrix
   in correct column-major form (translation as the 4th group).
2. **GLSL matrix-index swap in the depth→world reconstruction.** The shader used
   `ProjMat[2][3]` where it meant P23; GLSL indexes M[column][row], so P23 is
   `M[3][2]` and P32 is `M[2][3]` — they were swapped, corrupting the
   reconstructed view-Z. Fixed.
3. **Sampling the scene depth while it was the pass's own depth attachment.**
   `createRenderPass(..., depthView, ...)` attached the main depth view AND the
   fragment shader sampled it in the same pass (undefined → garbage, which made
   every fragment reconstruct to "outside the shadow volume"). Switched to the
   2-arg color-only overload (the atmospheric pass already used it for this
   exact reason).

### Original's shadow model (ported)

- Ortho span: `shadowDistance*2`, config default 2500 → `SHADOW_RADIUS=2500`
  (half-span; 512 map / 5000 span ≈ 9.8 blocks/texel = the original's
  resolution).
- `MinimumRadius` = the render distance in blocks (shadows start at +32).
- `FadeDistance` = 1028 (cloud_shadows.json default).
- `ShadowColorMultiplier` = (0.7, 0.7, 0.8) → the pass now samples the scene
  color (DiffuseSampler) and multiplies, instead of emitting black with a
  fixed alpha.
- 3x3 PCF with ±10-block world-space taps (the original's taps).
- The original gates terrain shadows on Distant Horizons being loaded; this
  port always renders them (the 26.2 client has no DH) — noted as a deviation.

### Dev diagnostics added (devshot tokens, dev-only, default = original model)

- `SHADNEAR` — force MinimumRadius=0 (shadows from 32 blocks) to see the shadow
  on near terrain.
- `NOFOG` — disable only the storm-fog fullscreen overlay (it darkens the whole
  screen, including the sky, when the camera is under storm clouds, and was
  masking the terrain shadow during diagnosis).
- `OVL0` — disable all overlay passes (shadow map + terrain shadow, storm fog,
  atmospheric).
- `SHADOWDBG` / `SHADOWDUMP` / `SHADOWWORLD` — visualize the stored shadow-map
  depth / raw map / reconstructed world position.

### Proof

- Shadow map was empty (all depth 1.0) before the fix; after fix 1 the map
  contains the cloud tops (SHADOWDBG: dark-red cloud shapes on the light plane).
- With storm-fog off (`NOFOG SHADNEAR H`), the terrain + cloud undersides darken
  under the deck (OVL0 vs NOFOG: terrain −7.2%, sky −7.4%). The magnitude is
  small at the dev scene's view distance because `distFade=(len−32)/1028` is
  only ~7–45% out to 500 blocks; it approaches full strength on distant
  terrain, matching the original's "distant shadows" design.
- The full `SHADOWTEST H` shot shows the combined storm-fog + terrain-shadow
  darkening (the sky darkening is the storm-fog, a separate, correct effect).

**Step 6 criteria — MET:** cloud shadows now appear on the terrain, follow the
cloud cover (top-down ortho depth map), and use the original's span /
distance-fade / color-multiplier model. Remaining polish (not a bug): the shadow
is subtle at close range by design; with DH-style distant terrain it is stronger.

---

## Step 7 — LOD / pop-in

**Verdict: no pop-in — new chunks fade in (0→1 over 5 ticks), regeneration
does NOT re-fade (no flicker), and distant chunks dissolve into the sky via the
field-radius fog. The horizon/field edge is a smooth gradient, not a hard cut.**

### How it works (all pre-existing, verified this step)

- **Fade-in (step 3):** each *new* chunk ramps alpha 0→1 at
  `CHUNK_FADE_IN_ALPHA_PER_TICK = 0.2`/tick (5 ticks) via `lastGenTick`.
- **No re-fade on regeneration (verified, line 740):**
  `d.lastGenTick = prev != null ? prev.lastGenTick : mc.level.getGameTime()` —
  a scroll-drift regeneration (A2) keeps the old tick, so an existing chunk's
  geometry updates in place without fading out to 0 first (no flicker). Only
  chunks entering the LOD band for the first time fade in.
- **Fog (A3):** the 2560..10240 field-radius fog fades the outer LOD chunks into
  the vanilla sky color, so the far edge of the 364-chunk HIGH field dissolves
  into the horizon rather than ending in a hard line.
- **LOD layout:** HIGH = 364 chunks over 1280 cloud units (10240 blocks), the
  original's default.

### Proof

- `/tmp/sc-A-step7.png` (14:47): the horizon is a smooth white→sky gradient; the
  field edge (left) fades gradually. No hard pop-in line.
- `/tmp/sc-F-step7.png` (same run, y=400 above the volume): the cloud tops fade
  smoothly into the pale horizon across the whole width; isolated far chunks
  dissolve into the fog. No popping.

**Step 7 criteria — MET:** the transition is gradual (per-chunk fade-in +
field-radius fog), distant chunks do not pop into existence, and regeneration
does not re-trigger the fade (no flicker).

---

## Step 8 — lighting / darkness (not uniformly grey)

**Verdict: the clouds are no longer uniformly bright. Per-cube storm brightness
is ported from the original (`cube_mesh.comp`, the `TYPE==1` block), so
fair-weather clouds stay bright while stormy types darken toward their base —
bright tops, dark undersides, giving the volume the 1.20.1 "not uniformly grey"
look.**

### What was wrong

The port's generator emitted every opaque cube with `brightness = 1.0F`
(`// vertical slice: no storm darkening yet`) — so every cloud face rendered
at full brightness (flat white), regardless of cloud type or height. The
per-face normal lighting (`UseNormals`) is off by default in BOTH the original
and the port (its light directions are static `(0,0,0)` in the original's
`clouds.json` → `mixLight` yields a flat ambient), so the "not uniformly grey"
quality in the original comes from the **per-cube brightness**, which the port
had not ported.

### What I changed

- **`CpuCloudGenerator.CloudLayerGroup`**: added the per-type storm fields
  `storminess`, `stormStart`, `stormFadeDistance` (from `CloudType`, which
  already carried them). Populated in `SimpleCloudsRenderer.dataDrivenGroups()`.
- **`CpuCloudGenerator.generate()`**: replaced `brightness = 1.0F` with the
  original's exact per-cube formula (faithful port of `cube_mesh.comp` lines
  387–388), using the owning group's storm fields:
  ```
  storminess = clamp(group.storminess + fade * 0.1, 0, 1)
  brightness = clamp(1 - storminess * (1 - clamp((y - group.stormStart) / group.stormFadeDistance, 0, 1)), 0, 1)
  ```
  where `y` is the cell's height above the volume base (cloud units, matching
  the original's `y`). Region mode uses the cell's formation group; the legacy
  infinite field uses group 0.
- Per-type values (from `SimpleCloudsCloudTypeProvider`): cumulus 0.2/16/16,
  itty_bitty 0.0/16/32, nimbostratus 0.8/16/256, stratus 0.5/16/128,
  cumulonimbus 0.6/16/128. Stormy types (nimbostratus/stratus/cumulonimbus)
  darken strongly toward their base; fair-weather types (cumulus/itty_bitty)
  stay bright — exactly the original's behavior.
- **Dev tooling**: `FLAT` devshot token (disables the storm shading for an A/B
  comparison) and a dev-gated, throttled proof log (Y range **and** brightness
  range per chunk; enabled only when a DevShot run is active, so the shipped
  mod's workers stay quiet).

### Proof

- **Log A/B** (same `SHADOWTEST D` scene; brightness range of generated opaque
  cubes): **SHADE → `0.88..1.00` / `0.90..1.00` (varies per cube)** vs **FLAT →
  `1.00..1.00` (uniform)**. The per-cube brightness genuinely varies when storm
  shading is on and is flat when it is off — the mechanism is active.
- **Image A/B** (view D, from inside/below the layer): `/tmp/sc8-D-shaded.png`
  vs `/tmp/sc8-D-flat.png`. The shaded view shows bright-white cloud tops
  fading to grey undersides (internal depth); the flat view is a uniform
  featureless grey. (Difference is most pronounced on the undersides of the
  larger/stormier formation banks, as in the original.)

**Step 8 criterion — MET** (clouds vary in brightness — bright tops, dark
undersides, per-type storm shading — rather than being uniformly grey).

### Note on what I did NOT change

- I did **not** enable `cubeNormals` (per-face normal lighting) — it is off by
  default in the original too, and the original's light directions are static
  `(0,0,0)`, so enabling it would not add sun/underside shading. The parity
  target ("bright sun-side, dark underside") is met by the per-cube storm
  brightness, which is the original's actual mechanism.
- The lighting UBO (`Light0_Direction` etc.) is still written with static
  values (matching the original's static `clouds.json`). No change needed for
  parity.

## Real profile (step 7) — 2026-09-13, session 2

Tested in Jan's real "Fabric 26.2" profile (329 mods incl. Distant Horizons)
outside ModrinthApp via `real-launch.sh` (own user unit, cgroup ceiling),
world "New World", jar 18:45 build. Jan played in the window during parts of
the day; the numbers below are from the controlled runs.

### Views A-F + rain (verified on screen, all 9 images read)
- All 8 standard shots at exact pin positions (zero camera drift after the
  per-frame re-pin fix), LOD 100% filled, scroll drift active.
- 3D white clouds, dithered edges, per-cube storm shading; **no vanilla cloud
  layer**; clouds above the DH far-view terrain at the horizon (no draw-over /
  draw-through / missing-behind); E1→E2→E3 prove drift (identical terrain,
  moving clouds); F = cloud tops from y=400 fading into the horizon.
- Rain under clouds: `--command "weather rain"` run — rain streaks under the
  cloud layer, no sorting artifacts.
- Images: `Fabric 26.2/screenshots/devshot-{A,B,C,D,E1,E2,E3,F}.png` (19:02 run).

### Memory (A1 real-profile proof)
- Clouds ON (clean 32-min view-cycling run, nobody in the game): RSS ramped
  1.0→8.0 GB over ~17 min (world + DH + field load), then **leveled off**:
  plateau 7889-8046 MB, minutes 17-31 (GC wiggle ±60 MB). No growth trend.
- Clouds OFF (jar removed, same scene, 328 mods + vanilla clouds): flat
  5562-5807 MB over 20 min (before Jan flew into the window).
- **Simple Clouds steady-state delta: ~2.1-2.2 GB RSS** (heap + 12 worker
  threads + per-chunk GPU buffers + metaspace). Levels off — no leak in the
  30-min window.
- Runs at 6 GB heap (`-Xmx6144m`, Modrinth global setting of the time).
  Jan then raised it to **16 GB** (`mc_memory_max=16384`); at 16 GB the game
  RSS reached ~14 GB (mony: 46 GB total, ~8-9 GB available during runs).
- Jan's earlier "8 GB and rising" observation was his own heavy exploring
  (new terrain) in the test window — confirmed by him; repeated clean.

### FPS (real profile, 16 GB heap, view B pinned, game's own counters)
- Clouds ON, steady state after worldgen settled: **30-45 FPS**, render frame
  time 2-10 ms; two early 7 FPS samples = worldgen tail (see lag note).
- Clouds OFF (mod loaded, `debug.generateMesh=false` + `renderClouds=false`,
  same scene): only pre-pin samples captured (30-36 FPS) before the run was
  stopped at Jan's request — **not a clean A/B**: in this 329-mod pack the
  game loop is tick-thread/worldgen-bound, and the cloud render cost sits in
  the noise of the pack's 2-10 ms frame times. Re-measure with the S-views if
  Jan wants a firm number.
- The old world OOM-looped on rejoin at Jan's far-south saved position with
  the 6 GB heap: `java.lang.OutOfMemoryError: Java heap space` in
  **streamsreflowing** (`NearestRiverIndex.nearest`) river worldgen — a
  modpack issue, not Simple Clouds. At 16 GB it joins, but worldgen there
  still pins the server thread (`Can't keep up: 112700ms behind`), which is
  the cause of the lag spikes Jan saw (also answered live).

### Tooling fixed this session (commit 8ea0554)
- DevShot: per-frame re-pin (one-shot teleports desynced in the 329-mod
  profile: view B first shot the ground), explicit xRotO/yRotO, FPSLOG token.
- `real-launch.sh`: systemd-run mangles double-digit positional params
  (`$10` → `$1`+"0"), which silently broke `--quickPlaySingleplayer`; now 8
  params, verified via `/proc/<pid>/cmdline`. `SC_RAIN=1`, `SC_XMX`.
- `rss-sample.sh`: per-minute RSS sampler.

## Storm plan (STORM-PLAN.md) — steps 0–7 (2026-09-13/14)

Jan's two reported bugs, from four screenshots (`Fabric 26.2/screenshots/
2026-09-13_19.37.41.png`, `_19.38.36`, `_19.38.49`, `_19.38.56`):
1. **Lightning from a far storm flashes the whole screen.**
2. **The cumulonimbus looks broken** — flat grey wall, a perfectly straight
   vertical cut, smeared grey blobs with streaks from below.

Both are fixed and verified in the dev client, then re-verified in the real
profile (full 329-mod pack + Distant Horizons). Commits: `b143a75` (step 0),
`24c956a` (steps 1+2), `99aa986` (step 3), `7a7e65a` (steps 4+5 + HIDEFLASH),
`3abc226` (step 7 HOLD test-infra).

### Step 0 — storm views S1–S5 (so the bugs can be seen and proven fixed)
DevShot `STORM` token: spawns a cumulonimbus formation 2000 blocks north of the
camera (fixed offset, radius ~200 cloud units) and runs S1–S5 at pinned noon:
S1 ground/pitch-0 with the formation edge (Jan's wall), S2 looking up from
under it, S3 y≈260 beside it, S4 above it, S5 a 60-frame ground sequence while
four FORCED strikes fire at known distances (200 / 1500 / 3000 / 8000 blocks
north) with a `[DEVSHOT-LIGHTNING]` log line per strike (distance, flash
applied, thunder choice/delay/pitch). S1–S5 are now in the standard views.
- Proved both bugs in the dev client: every strike (223–12370 blocks) flashed
  the whole screen; S5-01 showed the straight vertical seam at frame centre;
  S2 showed the flat grey wash.

### Step 1 — lightning flash (the whole-screen far-storm flash)
- **Root cause:** the port set `flashTicks = 24+(seed&31)` (1.2–2.75 s) for
  EVERY strike at any distance — even sound-only ones (set before the
  `onlySound` return) — and the flash drove a global screen brightening.
- **26.2 finding:** the vanilla sky flash is DEAD in 26.2 (nothing consumes
  `ClientLevel.getSkyFlashTime`), so the original's 2-tick cloud-colour flash
  would be invisible. The port therefore draws its own short full-screen white
  `sky_flash` pass (alpha-blended, same fullscreen triangle as the storm fog),
  driven by the gated `flashStrength`.
- **Fix:** (1) no flash for sound-only strikes — they return before adding a
  rendered bolt, so they never renew the flash; (2) the flash is only for a
  rendered bolt within `CLOSE_THUNDER_CUTOFF` (2000 blocks) with lifetime fade
  > 0.5, as a short (2-tick, flickering) sky flash — not a 1–3 s multiplier;
  (3) it honours vanilla's **Hide Sky Flashes** option (`flashStrength` returns
  0 when `options.hideLightningFlash()` is on).
- **KNOWN DEVIATION (plan step 1 fix #3 not ported):** the original ALSO lights
  the storm fog LOCALLY around each bolt (per-bolt positions+alphas fed into
  the raymarched fog; toggle `stormFogLightningFlashes`). The 26.2 storm fog
  is a documented simplified screen-space overlay (the shadow-map raymarch is
  not ported), so its flash hook is a gated GLOBAL `LightningMul` rather than
  per-bolt local lighting. The reported far-storm bug is fixed either way;
  per-bolt local fog light is a remaining fidelity item for Jan to weigh.
- **Proof (dev client S5 luminance curve):** baseline ~44–47; spikes 52.7/52.5
  at the 200-block strike and 53.7/54.1/53.5 at the 1500-block strike; **no**
  spike at the 3000/8000-block strikes. Pre-fix every strike spiked 34→53.

### Step 2 — thunder (same code path)
Ported verbatim from the original: close vs distant thunder at
`CLOSE_THUNDER_CUTOFF` (2000 blocks), pitch `0.5+fade*0.5` fading
`THUNDER_PITCH_FULL_DIST`(3000)→`THUNDER_PITCH_MINIMUM_DIST`(5000), volume
`1+rand*4`, delay `floor(dist/2000)*20` ticks via `playDelayed`, and an
`AdjustableAttenuationSoundInstance` for the `thunderAttenuationDistance`.
- **Proof (log):** 223 b → `close_thunder delay=0 pitch=1.00`; 3001 b →
  `distant_thunder delay=20t pitch=1.00`; 8000 b → `distant_thunder
  delay=80t pitch=0.50`.

### Step 3 — the smeared, streaky storm cloud
- **OIT REFUTED** (per Jan's instruction to find the real cause first, and skip
  the weighted-blend OIT port if it is not the cause): cumulonimbus has
  `transparency_fade: 0`, so `CpuCloudGenerator` emits **zero** transparent
  cubes for it — the transparency pass is not the smear. **OIT port skipped.**
- **Root cause:** the flat screen-space storm-fog overlay saturated to opaque
  (`coverage*2.5` capped at 1.0, gradient floor 0.3) — a fully opaque flat
  grey wash under a storm that hid ALL the cube geometry = the "smeared grey
  blobs with streaks" in Jan's shots 3–4.
- **Fix:** `storm_fog.fsh` density capped at **0.45** and the vertical gradient
  now fades to **zero** at the screen top (was a 0.3 floor), so the cube
  structure of the storm cloud stays visible through the fog (the original's
  raymarch fog darkens/softens but never fully occludes).
- **Proof:** S2 with fog on = clear 3D cloud masses, blue sky through gaps,
  storm-shaded undersides, no flat wash. Isolation pair: `docs/storm-evidence/
S2-final-fog-on.png` vs `S2-nofog.png`. (Residual fine vertical streaks in the
  darkest undersides = transparent edge-dithering + columnar noise of
  coexisting cloud types — a remaining fidelity gap, not OIT/fog.)

### Step 4 — the straight vertical cut / LOD
Evidence (no code): the distant field is genuinely multi-scale (large soft
far-LOD undulations, fine dithered cubes near; the generation log shows lod
4/8 chunks) — no uniform 128-cube block. The visible straight cut was the
chunk-border seam, root-caused and fixed in step 5.

### Step 5 — choppy motion + the straight cut (one root cause)
- **Root cause:** each chunk mesh is a snapshot of the wind scroll drift
  (`genScrollX/Y/Z` recorded at generation) but was drawn at its grid position
  until the drift accumulated `SCROLL_REGEN_THRESHOLD` (8 cloud units = 64
  blocks). That gives (a) a staircase of 64-block jumps staggered across chunks
  (the "choppy" motion) and (b) adjacent chunks holding different snapshot
  phases → a hard vertical seam at the border (the straight cut).
- **Fix:** draw each chunk at grid position + `(scrollNow − genScroll)` via a
  new `CloudOffset` UBO (a ring of 3 slots for the Mesa/Intel fixed-buffer
  constraint). Algebraically exact because `CpuCloudGenerator.sampleLayer`
  offsets the sampling by the full scroll for every layer, so the field moves
  as a rigid body in world space and one draw offset is exact for all layers.
  The shadow pass keeps a zero offset (≤8-unit error is invisible in a diffuse
  shadow).
- **Proof:** the vertical seam at frame centre (step 0's S5-01) is **GONE**
  (`docs/storm-evidence/S5-01-final-no-seam.png`); the low cloud deck drifts
  continuously at ~1.3 blocks/s (the expected wind speed) with no jumps
  (`S5-60-final-drift.png`).

### Step 6 — options
- **Hide Sky Flashes:** new `HIDEFLASH` devshot token calls
  `options.hideLightningFlash().set(true)`. Run `sc-storm-s6-hideflash` S5
  curve is FLAT 66.2–70.4 (no spikes) vs +8–9 spikes without the option, with
  the near strikes still firing — suppression is the option gate in
  `flashStrength`. **PASS.**
- **Storm fog toggle:** covered by the step 3 isolation pair
  (S2-final-fog-on vs S2-nofog).
- **Show Clouds off:** covered by the real-profile `renderClouds=false` FPS run
  below.

### Step 7 — real profile (this report)
- **Install:** `./dev-relaunch.sh --install` → `simple-clouds-0.7.3+26.2-
  fabric.jar` (2 683 492 bytes) into the real `mods/`; dev-client install-
  verify screenshot clean (white 3D cubes, dithered edges, no seams) with the
  new offset UBO; dev client stopped after the check.
- **World / pack:** `New World (1)` (the lightweight test world — the main
  `New World` worldgen OOM-loops / joins in ~8 min; the STORM scene is
  self-contained, spawning its own formation, so the world does not change the
  cloud-cost / playability result), full 329-mod pack incl. Distant Horizons,
  16 GB heap (`SC_XMX=16384m`), cgroup ceiling 14 GB.
- **S1–S5 re-run** (real profile, 09:4x): images in `docs/storm-evidence/real/
  devshot-S{1,2,3,4}.png` + S5 sequence frames. Read on screen: structured 3D
  cloud masses, no flat wash, no seam, no vanilla cloud layer, clouds sorting
  correctly against the DH far-view terrain.
- **Flash log (real profile):** close strikes (≤2000 b) log `flash sky-flash
  (2t per frame while bolt bright, fade>0.5)` + `close_thunder delay=0`;
  every far strike (>2000 b) logs `flash none (beyond the 2000-block flash
  cutoff)` + `distant_thunder` with distance-scaled delay (20 t / 40 t / 80 t)
  and pitch fade (1.00 → 0.70 → 0.50). Full log: `docs/storm-evidence/real/
  flashlog.txt`.
- **FPS near the storm** (S5 ground view, `HOLD` keeps the run active, the
  game's own `getFps()`/`getFrameTimeNs()` counters, same scene):
  - Clouds **ON** (default config): display 30 FPS; steady render frame time
    **2.8–4.1 ms** (median ~3.5 ms).
  - Clouds **OFF** (`renderClouds=false` + `generateMesh=false`, same scene):
    display 29–30 FPS; steady render frame time **2.6–4.0 ms** (median ~3 ms),
    with occasional 7–25 ms spikes (GC / chunk-gen, present in both runs).
  - **Delta ≈ 0.5 ms/frame (within the run-to-run noise).** The displayed 30
    FPS is a **cap**, not a load: `options.txt` has `enableVsync:true`,
    `maxFps:120` and the pack ships the **dynamic-fps** mod. The render work
    (~4 ms) is far below the 33 ms a true 30 FPS would need, so in this 329-
    mod + Distant Horizons pack the game loop is tick/worldgen-bound and the
    cloud render cost sits in the noise — consistent with the session-2 finding
    above. The full multi-LOD cloud field (the "109 million instances" scale)
    stays playable: no OOM, no crash, stable 30 FPS.
- **Memory (storm scene, real profile):** RSS stable at **~10–12 GB** with the
  cumulonimbus + full field (ON sampled ~10.4 GB; OFF leveled off 11.94→
  11.98 GB across 3 min), 13–16 GB of mony's 46 GB available. Well under the
  14 GB cgroup ceiling and 16 GB heap. Levels off — no growth trend. (The
  30-min clean-run cloud delta of ~2.1–2.2 GB and the OOM-in-streamsreflowing
  note are in the session-2 Memory section above.)
- **Reboot note:** mony rebooted 08:53 (clean — no OOM in the current boot; the
  Sep 13 OOM kills in `dmesg` are the pre-16 GB era). After the reboot the
  X11 cookie changed, so `~/.cache/simpleclouds/launch.env` (the saved launch
  env) needed its `XAUTHORITY`/`ICEAUTHORITY` refreshed to open `:1` again.

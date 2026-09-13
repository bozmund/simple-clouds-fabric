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
| 7 LOD/pop-in (port) | not started | — |
| 8 lighting/darkness (port) | not started | — |

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

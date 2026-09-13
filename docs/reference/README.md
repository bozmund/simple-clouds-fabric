# Simple Clouds — reference images (2026-09-13)

Official screenshots of the 1.20.1 mod, used as the visual ground truth for the
26.2 port (see `../../VISUAL-PARITY-PLAN.md`). Sources: the Modrinth project
gallery (api.modrinth.com/v2/project/simple-clouds, 2026-09-13) and the original
repository (`/tmp/simple-clouds-src`). The CurseForge gallery page could not be
fetched (Cloudflare 403 for non-browser clients); the Modrinth gallery is the
same official image set, so this does not block the comparison.

## How to read them

The port's devshots are taken at NOON in the dev world (CloudClean), with the
camera at fixed positions (views A–E, see `VISUAL-PARITY-PLAN.md` step 0).
Compare against the DAYTIME images below; dusk/night images are color
references only (step 5).

Baseline (pre-fix) devshots from 2026-09-13 are in `before/`:
`step0-baseline-{A,B,C,D,E1,E2}.png`.

### The dev world's standard view positions (CloudClean, probed at runtime)

- Views A/C/E: feet at (-55, 71, 133) — a sea-level forest plateau (surface
  71), camera yaw 157, facing open terrain. Eye at y 72.6.
- View B: (-55, 100, 133) — same XZ, held at y=100 (creative flight), pitch -15.
- View D: (-55, 260, 133) — same XZ, held at y=260, pitch -20 (inside/above
  the cloud volume, which spans world Y 128..2176 at default cloudHeight).
- The probe re-runs every run (DevShot.findBeach/findLand, deterministic
  scans around the player's XZ), so these move if the world or spawn changes.

## The images

| File | Shows |
|---|---|
| `modrinth-stratus.png` | **View-A reference.** Sea-level camera over a shallow lagoon; a low stratus sheet whose BASE is a flat horizontal line clearly above the water surface, extending to the horizon, bottom edge darkened/fading. The gap between horizon and cloud base is the altitude signature (cloudHeight, default 128). |
| `modrinth-cumulus.png` | Classic cumulus puffs: distinct cube structure, flat-ish tops, slightly darker bottoms/undersides, puffs and flat stratus layer both reaching the horizon. |
| `modrinth-cumulus-dusk.png` | Same cumulus scene at dusk (color reference: warm tint, darkening). |
| `modrinth-cumulus-superflat.png` | Cumulus over a superflat world — clean altitude reference: cloud base well above a flat ground plane, no terrain ambiguity. |
| `modrinth-small-cumulus.png` | Smaller cumulus cells, denser packing. |
| `modrinth-itty-bitty.png` | Itty-bitty cloud type (small, low, scattered cells). |
| `modrinth-stratocumulus-shadows.png` | Stratocumulus with terrain cloud SHADOWS visible on the ground below — the shadow pass must darken terrain under cloud mass. |
| `modrinth-stratus-cumulonimbus.png` | A stratus sheet next to a tall cumulonimbus tower (layer altitude spread: stratus low, cb towering far above). |
| `modrinth-cumulonimbus.png` | Cumulonimbus tower: very tall (layers up to 256 cloud units ≈ 2176 blocks at default cloudHeight), dark base, bright top. |
| `modrinth-distant-nimbostratus.png` | Distant nimbostratus: dark flat sheet far away, fading into the horizon. |
| `modrinth-nimbostratus.png` | Nearer nimbostratus (storm sheet). |
| `modrinth-distant-storm-dusk.png` | Storm at dusk from a distance (storm fog darkening + dusk color). |
| `modrinth-underneath-storm.png` | Camera BELOW/inside a storm sheet: dark underside, storm fog graying the scene. |
| `modrinth-storm-cumulus-front.png` | Storm front with cumulus edge. |
| `modrinth-rainy-weather.png` | Rain under stratus (rain quads + wet/dark look). |
| `modrinth-cumulus-night.png` | Cumulus at night (dark blue/white contrast). |
| `modrinth-dh.png` | Simple Clouds + **Distant Horizons** together (step 7: cloud/terrain sorting at extreme range). |
| `modrinth-cumulonimbus-dh.png` | Cumulonimbus with DH terrain in the distance. |
| `repo-title-banner.png` | Mod title banner (from the repository README, i.imgur.com/eAtuHMR.png). |
| `repo-icon.png` | Mod icon (repository `simpleclouds.png`). |

## Step 2 evidence (the port's LOD, 2026-09-13)

The port's LOD field (step 2) extends to ~10,500 blocks with 4 LOD levels
(lod 1/2/4/8; cube spacing + radius grow with the LOD). The world's own
formations are sparse, so the `BIG` devshot token spawns a large stratus deck
(radius 1200 cloud units = 9600 blocks) to exercise the distant coarse chunks:

- `step2-lod-A.png` (sea level, horizon): the sky is **filled to the horizon**;
  nearby clouds are finer and the distant ones are **coarser (bigger blocks)**
  and fade to white. Compare with `before/step0-baseline-A.png` (pre-step-2)
  where the clouds ended a few hundred blocks out and the sky beyond was empty.
- `step2-lod-C.png` (straight up, 56 blocks below the stratus): the near stratus
  ceiling is solid (the LOD fill + step-3 fade-in), not the dithered mid-fill
  state seen in the baseline.

Field extent (dev log): chunks at X = ±1312 cloud units (±10,500 blocks) across
lod 1 (near, ~13k cubes), lod 2 (~20k), lod 4 (~12k), lod 8 (~5k, far). No
off-thread generation failures.

## Step 3 evidence (fog + fade-in, 2026-09-13)

The cloud fog range is now relative to the vanilla render distance in blocks
(512..1536 for the 32-chunk dev world), and the fog color is the vanilla sky
color (captured via `MixinFogRenderer`; a sun-angle sky-blue approximation is
used when the 26.2 FogData color is (0,0,0) in the dev client). Clouds are
clear within the render distance, then fade to the sky at the horizon.

- `step3-fog-A.png` — distant clouds fade smoothly into the sky (atmospheric
  haze) instead of ending in a hard edge.
- `step3-fog-C.png` — straight up through the dense stratus deck (fogDistance
  is XZ-based, so overhead clouds are not fogged).

Also in step 3: the per-chunk fade-in (step 1 head-start) — fresh chunks fade
0→1 over 5 ticks so new LOD content does not pop in.

## Step 4 evidence (wind drift, 2026-09-13)

The clouds were FROZEN (scroll/wiggle hardcoded to 0); now they drift. The port
generates each chunk once (frozen noise), so the drift is a pure translation
baked into the cloud view matrix each frame: drift = -getScroll(partialTick) (the
original's noise sample is (worldPos + Scroll)/scale, so its clouds move by
-Scroll). The original's scroll speed (speed*0.0001 rad/tick) keeps the drift
subtle (~1 block/s at speed 1.0), matching the original.

- `step4-drift-E1.png` / `step4-drift-E2.png`: the motion test (E = fixed camera,
  10s apart; the `FAST` devshot token cranks the speed to 32x for the test). The
  dev log confirms the drift moved between the two shots (scroll X -19 -> -38, Z
  -98 -> -93), so the clouds are translating, not frozen. A pure translation of
  static shapes means no pop-in, no morphing, no stretching (the shapes are
  always present and just move). (E2 happens to be at dusk -- the devshot's
  noon-pin applies one frame late -- so the cloud shapes are darker there; the
  drift itself is what the scroll log proves.)

## Key observable properties (what the port must reproduce)

1. **Altitude:** cloud bases float above sea level — the lowest layer (stratus,
   cloud-space y=0) sits at world y = cloudHeight (default 128). From a
   sea-level camera the base is a flat line just above the horizon, never at
   the water surface.
2. **Range:** clouds extend to the horizon (effective radius ≈ 10,000 blocks
   at LOD HIGH), coarser with distance, no visible ring/edge where coverage
   stops (fog + LOD rings hide the boundary).
3. **Detail:** individual cubes (8 blocks at full detail) are visible up close;
   larger cubes at distance.
4. **Appearance:** bright tops, slightly darker sides/bottoms; transparent
   fringe around opaque cores; far clouds tinted toward the sky/fog color.
5. **Motion:** slow wind drift + shape morphing; new chunks dissolve in via
   per-chunk alpha (5-tick fade), never pop.
6. **Shadows:** terrain under cloud mass is darkened (separate shadow pass).

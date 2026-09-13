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

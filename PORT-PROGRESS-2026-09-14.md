# Fabric port implementation checkpoint — 2026-09-14

Status: implementation and validation in progress, NOT release-ready. No commit
or push performed. At Jan's explicit request, the candidate jar was installed in
the real profile at 18:01, before visual validation finished. That installed
candidate has SHA-256 0b0ff4901fe8693b489e8ccca333d5f8763782b806a98a788bcd2ee13915e00f.
Its predecessor is backed up in /home/jan/.cache/simpleclouds/real-backups.

## Preserved inputs

- Original live repository remains at `/home/jan/.local/share/ModrinthApp/profiles/Fabric 26.2/local-mods/simple-clouds`.
- Original dirty storm experiment saved in `/home/jan/.cache/simpleclouds/port-preserved-o2UtLgh1` (patch plus source/evidence archive).
- Clean HEAD baseline: `/home/jan/.cache/simpleclouds/port-baseline-PVV77Poe`.
- Isolated candidate: `/home/jan/.cache/simpleclouds/port-candidate-tgkFnnmU`.
- Full accepted plan saved in repository-root `plans/2026-09-14-complete-fabric-port.md`.

## Candidate implementation

- Local LOD-aware formation keys replace global invalidation; distant formations
  and saturated interiors do not invalidate unaffected chunks.
- Generation results retain job key/config/scroll identity, instead of being
  stamped with current renderer state. Pending ownership persists through upload.
- Shared generation batches stage bounded uploads and publish together. Old
  geometry remains visible until replacements are complete; failed batches do
  not replace it. Resource cleanup handles results after worker shutdown.
- Value-based grid comparison; scroll sign corrected for noise(x + scroll), with
  explicit conversion from cloud units to world blocks.
- Per-draw transform slices replace repeated writes to a three-slot offset UBO.
- Shared face rotation shader corrects inverted Y/Z face positions.
- CPU masks include a one-cell halo for neighboring chunk face culling.
- Buffer-pool retained-byte count now decreases on borrow.
- Renderer emits bounded 30-second generation summaries.

## Deterministic checks run

- Java compilation passes.
- Eight generation-key/translation cases pass.
- All 24 transformed cube corners match CPU face selection.
- Saturated formations emit no internal X/Z walls at LOD 1/2/4/8.
- 1000 pool borrow/release cycles use one allocation and maintain exact accounting.

## Visual evidence and test-harness defects

Baseline A–E and S1–S5 captured and inspected. Baseline already has tall walls,
seams and invalid views. Existing script's PASS was not evidence of visual parity.
Initial candidate motion still has visible changes at replacement; do not call
the user symptom fixed yet.

Tests were also defective: beach fallback used fixed Y=70 (could be underground);
S4 looked upward from above the cloud; SHAKE had no proper sequence support in
HEAD; FAST could be ignored or overwritten by normal server sync; test formations
were added only on the client, then replaced by authoritative server state.
Candidate harness corrects these, waits for freshly published generations, and
checks effective wind speed during every frame sample. Earlier FAST captures are
not valid 32x evidence. Test-world changes are restricted to isolated dev copies.

The render-log gate permits unrelated dev-environment problems (missing narrator,
sound backend, offline profile keys); audio fidelity has NOT been validated.

## Still required

Complete normal/32x motion validation, resolve remaining visible transitions,
assess preserved storm-raymarch candidate separately, restore/validate GPU
generation, complete remaining feature stubs, 30-minute backend tests, and
real-profile/Distant Horizons validation. No phase is declared complete yet.

## Resumption at 19:20 CEST

The offscreen old/new scene transition compiles successfully and was exercised
in the isolated dev client. A controlled 32x SHAKE run completed 41 frames with
no whole-field disappearance or dither/screen-door pattern. A normal-speed
41-frame run was also stable. The standard A–E view run completed all seven
captures and showed cloud geometry in every view. However, the C (looking up)
and D (above-cloud) captures are visibly over-bright/washed out. This is an
observed remaining lighting/fog parity issue, not a loading failure. The dev
unit was stopped after capture; the real Modrinth profile and installed jar are
unchanged.

The runtime log had only known isolated-environment warnings (offline
authentication/Realms, missing narrator/audio backend, and an old Jade game-rule
key); no Simple Clouds shader or render-pass error. CPU summary: 2,988 chunks,
0 failed, 37.8 s generation, 382.8 MB uploads, worst frame 187 ms. These numbers
are diagnostic only and do not establish final performance parity.

## Resumption on 2026-09-15, 00:21 CEST

Correction: the earlier C/D brightness diagnosis was not established. D is an
inside-cloud view at Y=260, not above-cloud evidence. F was also incorrectly
looking upward (-20 degrees) despite being called an above-cloud view. F now
looks down (+45 degrees). Its new capture shows cloud tops and terrain below.

Fixed the atmospheric layer's std140 layout: mat2 columns need offsets 64/80,
scalars begin at 96, and CloudColor begins at 144 (160-byte buffer). The old
144-byte buffer packed mat2 into 16 bytes, shifting the shader inputs. The layer
now reads a reusable separate scene texture instead of sampling its own render
attachment. The source target is recreated on resize and destroyed on close.
Also fixed the ray's aspect scaling to affect X only, and reject horizontal or
downward rays before the division by rayDir.y.

Build/run of the layout and scene-copy changes passed the render-log gate.
Five A/B/C/D/F captures were inspected in local evidence-0018-atmosphere.
The subsequent storm test exposed another harness defect: fallback ground
height used max(terrain, saved player Y), so after F it placed S1/S2 at Y=400.
That run was stopped and is NOT accepted as storm evidence. The fallback now
uses terrain height only. A corrected STORM run is in progress.

All edits remain in the isolated candidate. Original dirty repository work and
the installed real-profile jar are untouched; no Git mutation was performed.

### 00:26 outcome

The next storm run exposed a full saved formation list: addCloud refused the
fixture, leaving its coordinates NaN. The harness now replaces the dev scene
with its one storm fixture, checks successful insertion, and aborts before
teleporting if its coordinates are not finite. Corrected run produced all 64
frames; its log gate and final Gradle build passed (Gradle has no unit tests).

All six contact sheets in evidence-0025-storm were inspected. S1 and S3 show
storm geometry with dark bases; S4 shows geometry from above. S2 has plants and
terrain in the upward view: its reused ground height does not match the terrain
800 blocks away. Early S5 frames show terrain loading after the return teleport;
later frames contain complete terrain. Thus capture completion is NOT storm
parity acceptance, and S2 / early S5 are not suitable comparison evidence.
S5 also shows whole-scene flashes; the required local-only fog lighting has
not been implemented or accepted. Next: fix S2 local terrain placement and
wait for terrain rendering after teleport, then address storm coverage/local
light integration. The preserved experimental raymarch shaders remain separate.

## 2026-09-15 installation and continued storm work

User explicitly requested installing the new JAR and continuing the port.
Verified no Java game process before installation. Installed the 00:26 build
in Fabric 26.2/mods/simple-clouds-0.7.3+26.2-fabric.jar, SHA256
1fa5ff98efb5812d09d4b8e301a154895b36254d1058a6da3f9a328db327aa15.
Previous jar preserved in /home/jan/.cache/simpleclouds/real-backups/install-P3I4Jv2Z/.

Subsequent candidate changes (not yet installed): StormCoverage computes each
chunk's contribution to one shared 8x8 cloud-unit camera footprint instead of
the central 8x8 columns of every chunk. The renderer sums those fractions and
clamps to one instead of taking the maximum distant chunk value. Added tests
for distant storms, all four LOD scales, four-way boundaries, half coverage,
and invalid coordinates; all passed. Full Gradle build passed. Storm fog also
honors stormFogLightningFlashes; flashStrength still provides the existing
Hide Sky Flashes gating. This does NOT implement spatial per-bolt fog lighting.

Remaining limitation: coverage is attached to generated meshes and reflects
the camera at generation time; it can lag camera/formation motion. This needs
to be addressed by the planned live depth/coverage pass. New STORM runtime test
is in progress; its screenshots alone cannot validate that unfinished behavior.
Candidate jar SHA256 c74904f4f0091203c65881e1aec4bf3f1bcd0ad548d242150a7fc0d41385e334.

The corrected STORM run completed S1-S4 plus all 60 S5 motion frames and was
stopped afterward. S1 shows the storm edge, S3 the side profile, and S4 the
above-cloud profile. S2 now has a valid camera and cloud/sky geometry. S5 is
stable through its motion sequence, but the environment still produces bright
flash frames and only the existing screen-wide lightning pass is present; true
per-bolt fog lighting remains outstanding.

The run also exposed a resource concern: Dynamic Transforms UBO grew through
several high-water capacities up to 65536 slots during the 64-view sequence.
This is caused by one transform allocation per chunk draw and the two-scene
transition; it did not crash in this run, but it needs bounded ring reuse before
the planned 30-minute stress test. No new candidate was installed after this
observation.

RTX 5090 inspection showed ~29.8 GiB occupied by an existing workload; no model or
AI service was stopped. Dev launch environment currently selects Intel Vulkan.

## Resumption at 18:56 CEST

Jan requested Codex resume implementation after the Symbiote handoff. Symbiote
added ShaderFaceTest.java; the candidate generator still matched Codex's 14:17
version. Other current Symbiote sessions concern Reagent/StreamsReflowing, so
they were not interrupted.

New candidate change: CpuCloudGenerator returns an empty result immediately
after its 2D mask pass when no interior column has a valid owning formation.
Previously it still visited the entire 3D volume to skip every cell. Halo-only
formations do not count as interior; the unmasked preview convention is retained.
Borrowed buffers and all output counters are reset before returning.

Validation: compileJava passed; CpuEmptyChunkTest passed for empty and halo-only
coverage at LOD 1/2/4/8, stale buffer/counter reset, and retained unmasked preview.
CpuChunkSeamTest also passed at all four LODs. Standalone tests print the existing
Log4j no-provider diagnostic, which is unrelated to the assertions.

Visual testing pending: the real game is open (PID 117378 observed, profile Fabric
26.2). Host had only 4.6 GiB available RAM and no swap. The newly launched dev
unit was stopped to avoid memory pressure. Jan has been asked whether to close
the real game after saving. Do not report that aborted run as a passed visual
test or replace the installed jar with this newer unvalidated change.

Correction to the earlier handoff: visible changes and a generation backlog at
32x speed were observed, but the claim that CPU throughput caused the visual
instability was not established. Re-check with controlled formation/noise state.


## 2026-09-15 00:45–01:30 CEST (Claude)

Jan asked Claude to continue from `plans-claude-handoff.md`. The real game was not running; no
Symbiote/AI process was touched; only `simpleclouds-devclient` was stopped after each run. Nothing
was installed. Originals of changed files: `~/.cache/simpleclouds/claude-backups/20260915-004924-before-transform-ring/`.
The ordered next steps are in `plans-claude-handoff.md` (section updated 01:30).

### Changed files
- `client/renderer/v2/CloudsDrawPipeline.java`: `beginFrame()` resets `ownTransforms` once per frame;
  `frameTransform()` for every transform write (cap 16384/frame → "Simple Clouds ERROR", draw
  skipped); storm-fog pipeline samples depth (MATRICES_FOG_SNIPPET + DepthSampler, colour-only
  pass, UBO 4416 bytes); sky flash samples depth.
- `client/renderer/SimpleCloudsRenderer.java`: calls `beginFrame()` in `renderBeforeLevel`;
  summary field `transformWritesPeakPerFrame`; chunk results carry storm columns; per-frame
  `StormFogMap`; `collectFogBolts` (≤ 8 nearest bolts, one log line per bolt with the fog light it
  gets); DevShot hooks `setDevFogFlashes`, `setDevStormFogDebug`. Removed the global `lightningMul`.
- `client/renderer/v2/CpuCloudGenerator.java`: keeps the last chunk's storm columns
  (`copyStormColumns`, `lastStormXCells/ZCells`), also on the empty-chunk early return.
- `client/renderer/v2/StormFogMap.java` (new), `StormFogMapTest.java` (new, 1046 checks pass).
- `shaders/core/storm_fog.fsh` (rewritten: 24-step depth-aware march through the coverage map below
  the cloud base, local bolt light, debug modes), `shaders/core/sky_flash.fsh` (sky pixels only);
  both use the cleared depth (`d <= 1e-7`) as sky since build `ca85968f…` (not yet run).
- `client/DevShot.java`: S2 `groundAtTarget` (ground resolved at its own column); `terrainGate`
  (attempts 1 and 2, 90 s timeout → view refused with "Simple Clouds ERROR"); tokens `FOGDEBUG`,
  `NOFLASH`.
- `ShaderFaceTest.java`: parser fix (`find()` instead of `matches()` for `if (side == N) return ...` lines).
- `tools/claude-run.sh`, `tools/sheet.py`, `tools/imgstat.py` (new).

### Compile / standalone tests
`./gradlew compileJava` passes after every change; `./gradlew build -x test` at 01:25 passes, jar
sha256 `ca85968f915a5ece5e4bc36804ecbeb880ab659bc664c8d3f8c503b193b48ec4` (not installed, not validated).
Standalone: CpuChunkSeamTest, CpuEmptyChunkTest, ChunkBufferPoolTest (1000 cycles), ChunkGenerationKeyTest
(8 + 328 checks), ShaderFaceTest (37121 checks, after the parser fix), StormCoverageTest, StormFogMapTest
(1046 checks) — all PASS. ShaderFaceTest could not pass before the parser fix (verified by reading it).

### Runtime evidence (all in the candidate folder)
- `evidence-claude-0915-01-shake-normal` (00:50, SHAKE, transform fix only): 41/41 frames, log gate
  passed. Verified: Dynamic Transforms capacity max 32 (vanilla's ring and ours both 2→16, one →32);
  `transformWritesPeakPerFrame` 1196/1198; no cap error; failed chunks 0; worst frame 344 ms (fill),
  then 40 ms; heap 1.5–2.7 GB, direct 56 MB / 270 buffers, RSS 3.6–3.8 GB. Observed: no whole-field
  disappearance, no dither; faint stair-stepped shading at the top-left sky edge in later frames.
- `evidence-claude-0915-02-storm` (01:07, STORM, all changes, old sky threshold): 64/64, log gate
  passed (only the known libflite narrator error). Verified: capacity max 64; writes peak 1216;
  failed 0; worst 516 ms (fill) then 70/53 ms; direct 85 MB / 523 buffers; RSS 3.1 → 4.4 GB (short
  run). S2 at its own ground (Y 70), no plants. Gate: S1 after 636 ticks; S2–S5 after 46–48; S4
  "after −23 ticks" (game clock jumped back). Strike log: 223 blocks → local fog light + sky flash +
  close thunder; 1503 → sky flash, no fog light; 3001 and 8000 → no flash, no fog light, distant
  thunder (8000: 80-tick delay, pitch 0.5). Observed problems: S5-01..09 have NO terrain (gate
  attempt 1 insufficient); S4 is 100 % white (mean 255, std 0; older S4 ≈ 150–168) — unresolved;
  a dark streaky band at the horizon in all S5 frames; S5-18/19 clouds and horizon brighter.
- `evidence-claude-0915-03-storm-nofog` (01:14, STORM NOFOG, gate attempt 2): FAIL (evidence) 62/64
  — S3 and S4 refused after 1801 ticks ("terrain in view false"; errors logged). Verified: all 60 S5
  frames have terrain; without the fog pass the horizon band is gone (so the fog causes it); S2
  looks the same flat grey (cloud base, not fog). Observed: S5-07 and S5-22..24 whole frame
  including terrain brighter with the fog OFF (≈ the 200- and 1500-block forced strikes).
  Memory: RSS 5.26 → 5.18 → 5.18 GB over the last three 30 s samples, direct 108 MB / 621 buffers,
  failed chunks 0, writes peak ≤ 1048, capacity 64.

### Not done
Terrain gate for high/steep views (two attempts used — reassess), S4 white, depth-convention check
with FOGDEBUG, whole-frame strike brightening, fog horizon band, NOFLASH A/B, SHAKE FAST, A–F,
30-minute stress. No phase is declared complete.

## 2026-09-15 09:40–09:55 CEST — Claude review of Codex's 09:38 continuation (read-only)

Checked against the files on disk; no code, build or run changed. Installed Fabric 26.2 jar still
`1fa5ff98…` (verified); candidate jar `6c1f210d…` matches Codex's note; devclient inactive.

Confirmed (verified from `evidence-codex-0915-settled`): 64/64, no Simple Clouds error/refused view,
S3/S4 accepted through the no-ground-in-frustum fallback (probes 0/0, ground samples 869/801),
S5-01 gate with 209/224 frustum probes, DEVMEM max RSS 4.57 GB / direct 81 MB, transform capacity 64.
The DevShot diff matches the description (server-side teleport, render-state frustum probes,
nanoTime timeout, 3 s settle, 2 fresh batches + `isCaptureFieldSettled`).

Corrections / additions for whoever continues:
1. **The old S4 baseline was wrong (Claude's error).** `docs/storm-evidence/S4-*.png` (09-14) are
   plain blue sky with NO clouds, so "older S4 mean 150–168" is not a correct-looking reference.
   From the STORM spawn log (cell at cloud units -6.625x-396 = blocks -53x-3168, r 200u = 1600
   blocks) S4 (-53x2300x-3168, looking down) sits above the storm cell: white cloud tops are
   expected (suspected — cloud drift not accounted). Remaining question is only whether the flat,
   unshaded white matches 1.20.1; needs an original 1.20.1 S4 capture, not the 09-14 files.
2. **NOFOG / HIDEFLASH S3/S4 were shot on a half-built field** (log `state` before the shot:
   waiting 334–335 chunks, faces 88,584 / 286,691 vs 770,541 / 1,052,612 in the settled run).
   Their S3/S4 numbers (incl. the "square hole" S4 in the NOFOG sheet) are not comparable.
3. **Settled S3 is uniform grey with light streaks** (looking slightly up at y 260), while the 09-14
   S3 shows cumulus and blue sky. S3 is ~1440 blocks from the spawn centre, inside r 1600, so this
   is probably the new storm fog + rain under the cell (suspected). Verify with STORM NOFOG after
   the settle gate, and against 1.20.1.
4. **New fog artefact not in Codex's list:** from S5-42 on (settled), faint horizontal streak lines
   and a grey wash in the upper-left sky, right after the field grew (faces 445k -> 586k, batch 1153,
   waiting 358). Absent in the NOFOG sheet. Likely the same 24-step fog-march banding as the
   horizon band; add it to the fog-tuning pass criteria.
5. **Brightness metric blind spot:** `flash-metrics.py` ROI (10–90 % width, 80–98 % height) only
   sees near terrain (values cycle 169.12–169.19). It cannot detect sky/horizon flashes. Sky
   brightening in settled S5-07 and S5-22..24 (200 / 1500-block strikes, inside the 2000-block
   cutoff) is visible on the sheet and is probably the intended sky flash; near-terrain flash not
   seen (verified by ROI). Add a sky/horizon ROI before judging flashes.

Next order otherwise unchanged: re-run NOFOG / HIDEFLASH / FOGDEBUG / SHAKE FAST / A B C D F with
the settle gate; fog tuning (horizon band + upper-sky banding); 1.20.1 reference for S3/S4; then
the 30-minute LOOP stress. Nothing installed.

## 2026-09-15 09:51–10:15 CEST — Claude: handoff step 1 re-runs with Codex's settle gate

Jar `6c1f210d…` (unchanged, no code edits). Batch `tools/claude-batch-step1.sh` (systemd user unit
`claude-sc-batch`, log `claude-batch-0915.log`); each run via `tools/claude-run.sh`, only
`simpleclouds-devclient` stopped. New `tools/roi.py <dir>`: sky (0–35 % height) / horizon (50–62 %) /
terrain (80–98 %) means per frame, flags S5 frames ≥ 4 levels over the series median.
Installed jar still `1fa5ff98…`. Nothing installed.

| Evidence | Tokens | Frames | Errors / refused | Worst frame | RSS max | Transform cap |
|---|---|---|---|---|---|---|
| `evidence-claude-0915-10-nofog` | STORM NOFOG | 64/64 | none | 495 ms | 4.92 GB | 32 |
| `evidence-claude-0915-11-hideflash` | STORM NOFOG HIDEFLASH | 64/64 | none | 477 ms | 5.08 GB (heap 4025/4096 MB) | 64 |
| `evidence-claude-0915-12-fogdebug` | STORM FOGDEBUG | 64/64 | none | 494 ms | 5.12 GB | 64 |
| `evidence-claude-0915-13-shakefast` | SHAKE FAST | 41/41 | none | — (run < 30 s) | — | 64 |
| `evidence-claude-0915-14-abcdf` | A B C D F | 5/5 | none | 1037 ms | 2.93 GB | 32 |

Failed chunks 0 everywhere; every gate line present (S3/S4/C/D through the fallback, 0/0 probes).
All contact sheets inspected.

Verified:
- **Hide Lightning Flashes hides the sky flash.** HIDEFLASH S5-07 sky 179.7 vs 180.0 before, no spike at
  S5-22..24. Without it (NOFOG) the horizon band rises +10..+15 at S5-07 / S5-22..24 and at S5-51
  (a natural strike — 3 "flash sky" log lines in that run). Terrain band constant (169.1–169.2) in
  every run: no strike brightens terrain. Handoff item 4 closed, unless it shows up in real play.
- **Depth convention (FOGDEBUG, full-res S5-45 viewed):** sky pixels blue, clouds up to the horizon
  grey (farther = lighter), near terrain dark; sky flash in debug mode lightens only the blue (sky)
  pixels. The `d <= 1e-7` sky test is consistent. The dark horizon band in fog-on runs lies in the
  region FOGDEBUG classifies as sky → it is fog applied to sky rays (item 5 stands).

Corrections of Claude's 09:55 review (points 3 and 4 were wrong):
- **Grey S3 is not the fog.** HIDEFLASH is a NOFOG run and its S3 is also uniform grey (mean 105.3,
  std 2.0; settled fog-on 108.0 / 4.0), while this run's plain NOFOG S3 shows bright clouds
  (158.3 / 55.5). S3 content depends on where the growing storm cell is at shot time; the camera
  (y 260) is inside the cloud volume in some runs. Scene state, not a render defect (suspected).
- **The horizontal streak lines in the upper sky are geometry, not fog banding:** they are present in
  FOGDEBUG (distance view). Possibly thin high cloud layers seen edge-on; the 1.20.1 gallery
  (`docs/reference/modrinth-cumulonimbus.png`) also shows streaky high layers. Compare, do not "fix"
  in the fog shader.

New observations (not yet explained):
- **STORM runs are not reproducible scene-for-scene.** The cumulonimbus grows (300 t) while the gate
  waits a variable time, so S3/S4/S5 cloud cover differs a lot between runs (NOFOG S5: sky almost
  fully covered; settled/HIDEFLASH: blue gaps). Cross-run A/B of fog vs no fog is only valid on
  frames where the scene matches; for fog tuning, a fixed storm size (grow 0 or a fixed age at shot
  time) in DevShot would make comparisons valid.
- **Large field changes pop in ≤ 2 frames.** HIDEFLASH S5-21→S5-22: faces 508,189 → 623,009, sky band
  179.6 → 193.0 and stays (not a flash). Batch swaps cross-fade in `TRANSITION_NANOS = 300 ms`
  (SimpleCloudsRenderer ~l.400); the original grows clouds mesh-chunk by mesh-chunk, so a storm-growth
  step probably shows as a pop only in the port (suspected — check against 1.20.1 in motion).
- **S4 white depends on storm growth:** 96.9 % pure white (NOFOG), 76.7 % (HIDEFLASH), 68.6 % (settled).
  S4 camera Y 2300 is only ~124 blocks above the cloud-volume top (2176), right over the cell, so
  its top faces fill the frame. Probably view placement, not lighting; needs the 1.20.1 look from
  above before changing any shading.
- **SHAKE FAST:** clouds in all 41 frames, no whole-field disappearance; several frames (03, 19, 26,
  29, 33, 37, 40) show semi-transparent double-exposed clouds (batch cross-fades during fast motion).
  Not judged yet — compare with the original at the same speed.
- **Heap reached 4025/4096 MB** (HIDEFLASH, second DEVMEM sample); no OOM/GC error. Watch it in the
  30-minute stress (item 7).
- A B C D F: A/B terrain + clouds, C (looking up at y 181) nearly uniform white-grey, D blue sky with
  a cloud ring, F looks down through a cloud gap onto terrain. Plausible; no reference comparison yet.

Remaining, in order: (5) fog on sky rays — the horizon band's vertical 64-block streaks; the band
itself matches the 1.20.1 look under a distant storm, so tune the streaks, not the band; consider a
fixed storm size in DevShot first. Then 1.20.1 reference for S3/S4/C and the batch-swap pop,
then (7) 30-minute LOOP stress with heap watch, then (8).

## 2026-09-15 10:05–10:30 CEST — Claude: repeatable STORM fixture (next-steps item 1)

Cause (verified by reading the code): `CloudRegion.tick` scales the radius by
`tickCount/growTicks`, then shrinks it linearly to 0 at `existsForTicks`, and a region no spawn
region sees ages 20x per tick. The STORM fixture had grow 300 / lifetime 72000, so its size
depended on how long each gate waited. Also, `CloudManager.scrollAngle` (the noise phase,
`scroll = (cos, sin)(angle) * 100`, +0.0001 rad/tick) is saved with the world, so every run started
from where the previous one ended (phase -85,52 … -97,26 seen).

Change (only `client/DevShot.java`; backup `claude-backups/20260915-1025-before-storm-fixture/`):
the STORM fixture spawns with grow 0 and lifetime 2,000,000 (worst case 2 % shrink per run), and
`setScrollAngle(2.88)` (= Codex's settled phase) before the existing client→server sync. New token
`STORMGROW` = old behaviour. Spawn log line now prints grow/lifetime/angle. Build OK, jar
`35ab0b1496a3c90df34da3951dd57df9f38d82ba1be73167513cb3b429eba0e0` (NOT installed; installed
still `1fa5ff98…`). Side effect (unchanged in kind): the fixture stays in the dev world save and is
still present in later non-STORM runs (spawnTestFormation adds, does not replace) — now for longer.

Evidence (`tools/claude-batch-repeat.sh`, all 64/64, no errors, `tools/framediff.py A B`):
| Pair | Mean abs diff (0–255) | S3 | S4 |
|---|---|---|---|
| Before: STORM NOFOG Codex 09:2x vs Claude 09:52 | 16.75 | 62.78 | 45.60 |
| After: `20-storm-a` vs `21-storm-b` (both STORM) | 5.52 | 9.68 | 0.21 |
| After: `21-storm-b` (fog) vs `22-storm-nofog` | 7.96 (fog only) | 0.47 | 0.00 |

- b and 22 had identical face counts at every S shot (629100 / 1055599 / 1225055 / 604277 /
  615464) → same scene; their S5 difference (≈ 8) is the fog alone. Verified A/B pair for step 5.
- a vs b residual (5.5): run a (first after the build) waited longer at S1 (40.8 s vs 31.4 s), so
  the angle advanced ~0.02 rad more (phase offset ~2 units). Rule for comparisons: use runs whose
  `state … faces=` lines match at the S shots, or freeze the scroll during gate waits (not done).
- Fog effect on the matched pair (roi.py): horizon band 153.2 (fog) vs 201.6 (no fog), sky band
  identical (161.2), terrain identical (169.2). The dark band with vertical streaks is visible in
  every fog-on S5 frame, absent in NOFOG.
- Flash frames with fog on rise more on the horizon (+34..+35 at S5-06/23/24/56) than without fog
  (+8..+15) — the fog pass reacts to the sky flash (sky colour input) — check in fog tuning.
- **S4 is now 100 % white in every run** (all bands 255): with the storm at full size, S4 (Y 2300,
  124 blocks above the cloud-volume top 2176, over the centre) sees only top faces. This explains
  Claude's 01:07 all-white S4 (storm fully grown then). S4 needs a new placement (higher, or offset
  to the cell edge) to be informative — a DevShot view change, not a renderer fix.
- Memory: heap up to 3590/4096 MB, RSS ≤ 5.14 GB, direct ≤ 107 MB, transform capacity ≤ 64.

Next: S4 placement (small DevShot change), then fog tuning on matched pairs (horizon streaks,
flash reaction), 1.20.1 references, 30-minute stress.

## 2026-09-15 10:35–10:50 CEST — Claude: S4 moved to an oblique view

Change (only `client/DevShot.java`; backup `claude-backups/20260915-1045-before-s4-move/`): S4 was
Y 2300 straight down over the storm centre; now `(stormCx + 250u) * 8` = 2000 blocks east of the
centre, Y 2600, pitch 30 (down), yaw 90 (facing west, toward the centre). Class javadoc updated.
Build OK, jar `d91c38c0e7cb9e97067d4b0b5014aa45bb9b4256ac4371de3177b0817b2b9c35` (NOT installed;
installed still `1fa5ff98…`).

Evidence: `evidence-claude-0915-30-s4-storm` (STORM) and `-31-s4-nofog` (STORM NOFOG), 64/64 each,
no errors / refused views, S4 gate via the fallback (8.6 s / 16.6 s), transform cap 64, RSS ≤ 5.03 GB.
- S4 now shows sky at the top, the pale sky/void below the horizon (terrain 2500 blocks down is
  outside the terrain render distance), and the storm cell's top as a silhouette: mean 229.2,
  44.6 % pure white (was 100 % in every repeatable run). Fog on vs off at S4: 0.45 → the storm fog
  does not touch this view (camera far above the fog top).
- **Observed, now reproducible: the whole cell top is one flat 255-white area — no shading between
  top and side faces, no depth cues along the rim.** In the 1.20.1 gallery
  (`docs/reference/modrinth-cumulonimbus.png`) tops are bright but side faces show grey shading.
  Suspected cause (Codex 09:38): storm voxels at the top reach brightness 1 and `cubeNormals` is off
  by default, so everything clips. This view is the test for any fix; do not change shading without
  an original capture from a similar position.
- Repeatability across builds: S1 0.16 and S2 0.46 mean abs diff vs `21-storm-b` (verified same
  scene). S3 differs by 19 (field at shot time 1,129,973 vs 1,055,599 faces): S3 is still sensitive
  to gate timing — compare only runs with matching `faces=` at S3.

Next: fog horizon streaks on the matched pair (21-storm-b / 22-storm-nofog, or 30/31 for S1/S2/S5);
1.20.1 capture from above for the flat-white tops; 30-minute stress.

## 2026-09-15 10:55–11:15 CEST — Claude: "fog horizon streaks" investigated — not reproduced, no code change

New tools: `tools/fogdiff.py <fog.png> <nofog.png> <out>` (fog-only darkening of a scene-identical
pair: per-row bands, column profile, high-pass rms, autocorrelation) and `tools/bandtex.py <png>...`
(column texture inside rows 260–319 of single frames). No shader or Java change.

What the band is (verified on the matched pair 21-storm-b / 22-storm-nofog, S5-01 and S5-30):
- The fog pass darkens ONLY rows ~255–335: the pixels between the true horizon and the terrain
  silhouette. The S5 camera stands at Y 182; below the true horizon, ground beyond the terrain
  render distance is not drawn, so those pixels are "sky" (depth 0) whose rays point DOWN and march
  the full 800 blocks under storm cover → up to 140 levels darker (peak row 279). Cloud pixels,
  near terrain and the sky above the horizon change by < 0.4 levels.
- The upper edge is soft (rays near horizontal reach the fog only at the far end); the lower edge is
  the terrain silhouette (terrain rays are short). The 1.20.1 gallery's distant cumulonimbus shows
  the same arrangement (dark band under the base, ending at the sea horizon, soft left/right fade).

Streaks (the 01:07/01:14 description "vertical streaks at the 64-block cell spacing"):
- Fog-only difference: after a 41-px high-pass the column texture is rms 1.0–1.06 levels with
  autocorrelation only at 2–5 px = the interleaved-gradient-noise dither of the 24-step march. No
  regular spacing at the 30–60 px a 64-block cell would give at 800 blocks.
- Single frames, band rows 260–319, rms after high-pass: 01:07 build (old sky threshold) 0.62–1.82;
  Codex settled 1.74–1.86; current fixture fog-on 2.20–2.21; fog-OFF controls 2.07–2.40 (terrain
  silhouette). Fog-on frames carry no more column texture than fog-off frames. → **Not reproduced;
  the earlier "streaks" description was Claude's misreading of contact-sheet thumbnails** (the band's
  broad soft light/dark columns, visible in the 01:07 zoom, are the storm coverage along each ray —
  rain-curtain-like — and the fine grain is dither). Handoff item 5 is closed as "no defect found".

Remaining, not defects by evidence but open parity questions (need 1.20.1 captures of the same
scene): band darkness/colour (port: FogColor 0.04/0.045/0.06, alpha cap 0.8; original: shadow-map
colour × ColorMultiplier, density grows with distance, 200 quadratic steps to ~7900 blocks); the
fine dither grain (visible only zoomed; STEPS 24 → 48 would halve it at twice the pass cost).

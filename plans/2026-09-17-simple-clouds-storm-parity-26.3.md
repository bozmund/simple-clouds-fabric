# Simple Clouds — storm parity with the 1.20.1 original, Minecraft 26.3, Distant Horizons

Part of the MASTER plan `/home/jan/simple-clouds-fabric/plans/2026-09-17-MASTER-clouds-storm-26.3.md`
(read its hard rules first). Accepted by Jan on 2026-09-17. Repository: `/home/jan/simple-clouds-fabric`.
Work branch: `work/storm-parity-26.3`, created from `fix/cloud-noise-parity` (0eccfeb). One commit per
verified step, push after each commit, never force-push, never merge into `main`.

## What you need to know first

- Read: `CLAUDE-HANDOFF-CLOUD-MOTION-2026-09-15.md`, `PORT-PROGRESS-2026-09-14.md` (skim),
  `docs/reference/README.md`, the header comment of `src/main/java/dev/nonamecrackers2/simpleclouds/client/DevShot.java`.
- Dev loop: `./dev-relaunch.sh` builds and starts ONE isolated dev client as user unit
  `simpleclouds-devclient` (world `run/saves/CloudClean`), writes `run/devshot.request` with the
  tokens, and waits for screenshots. `tools/claude-run.sh <evidence-name> <TOKENS...>` wraps a full run:
  refuses if Jan's real game runs, saves log + screenshots + `contact-sheet.png` into `<evidence-name>/`,
  prints summaries, stops only the dev client.
- DevShot tokens: views `A B C D F` (+ `E` motion), `LOOP`, `STORM` (storm views S1–S5, S5 = 60 motion
  frames with forced strikes), `STORMGROW`, `NOFOG`, `HIDEFLASH`, `OVL0`, `NOSPAWN`, `FAST`, `SHAKE`
  (41 frames), `HOLD`, `FPSLOG`, `BOLT`.
- Tests: `bash tools/codex-check-tests.sh` (all must PASS). Build check: `./gradlew --console=plain --max-workers=8 build`.
- 30-min stress: `tools/claude-stress.sh [seconds]` — it currently writes into a FIXED folder
  (`evidence-claude-0915-40-stress`); step 1 makes the folder a parameter.
- Frame change numbers: `bash tools/motion-metrics.sh <folder>` (descriptive only).

## Step 1 — Fix DevShot capture gates

**Problem (observed 2026-09-17):** views are refused after 90 s with
`scene still loading after 90000 ms (column resolved true, chunks loaded true, terrain in view false, sections rendered true, compile queue 0, cloud ready false, batches 110/6); view refused`
- STORM run `evidence-claude-0917-02-storm`: only S2 and S4 captured; S1, S3, S5-01 refused.
- SHAKE FAST run `evidence-codex-0917-motion-fast` (log `evidence-codex-0917-noise/motion-fast.log`): SHAKE-01 refused.
- Normal SHAKE (`evidence-codex-0917-motion-live`) passes.

**Do:**
1. Create the work branch. Read the gate code (search `scene still loading` in DevShot.java, ~line 1560).
2. For each refused view find which condition stays false (`terrain in view`, `cloud ready`) and why:
   sky-facing storm views have no terrain in view; with FAST (and during storms) clouds keep
   regenerating, so "cloud ready" (no pending batches) may never become true.
3. Change the gate minimally: per-view requirements (terrain-in-view only for views that look at
   terrain), and a bounded "clouds settled enough" rule (e.g. pending batches below a threshold for
   N frames, or all batches inside the view frustum ready) instead of "zero pending". Never lower the
   bar so far that half-built clouds get photographed: look at the images.
4. Make `tools/claude-stress.sh` take the evidence folder name as an argument (keep the default
   behaviour documented, but never overwrite an existing folder — refuse instead).
5. Run `bash tools/codex-check-tests.sh`, then:
   - `tools/claude-run.sh evidence-sym-0917-01-storm STORM` → expect all storm frames (64 in earlier
     runs: S1–S4 + 60 S5) and no "view refused".
   - `tools/claude-run.sh evidence-sym-0917-02-shakefast SHAKE FAST` → 41/41.
   - `tools/claude-run.sh evidence-sym-0917-03-shake SHAKE` → 41/41 (regression).
6. LOOK at the contact sheets: clouds fully built (no missing chunks, no half-transparent double images).

**Acceptance:** the three runs complete with all frames, tests pass, sheets look right. Commit
`DevShot: per-view capture gates (storm sky views, FAST motion); stress output folder argument`, push.
**Then:** MASTER plan → tick Step 1 → Step 2 below.

## Step 2 — Reference captures of the ORIGINAL mod (Forge 1.20.1)

**Goal:** see how the original looks and behaves, in scenes that the port can reproduce exactly.

1. `git clone /tmp/simple-clouds-src /home/jan/.cache/simpleclouds/original-1.20.1` (if `/tmp/simple-clouds-src`
   is gone, clone `https://github.com/nonamecrackers2/simple-clouds` and check out the 1.20.1 source
   matching Modrinth version `0.7.3+1.20.1-forge`; record the commit). Create branch `reference/devshot`.
2. Java 17: `nix shell --impure --expr '(builtins.getFlake "path:/home/jan/nixos-symbiote").inputs.nixpkgs.legacyPackages.x86_64-linux.temurin-bin-17'`.
   ForgeGradle 6: `./gradlew --max-workers=8 runClient` in its own run directory. Start it as a user
   unit (e.g. `simpleclouds-refclient`, `MemoryMax=10G`) with the live `DISPLAY/WAYLAND_DISPLAY/
   XAUTHORITY/XDG_RUNTIME_DIR` — copy the pattern from this repo's `dev-relaunch.sh`. It runs offline
   with a dev player name; that is fine.
3. Port a MINIMAL DevShot into the reference copy (new class, client tick + render hooks via Forge
   events or mixins): request file `devshot.request` in the game dir with tokens, `Screenshot.grab`
   captures, pinned camera, noon, clear vanilla weather, no HUD. Needed tokens:
   - `CREATEFLAT <name> <seed>`: if the save does not exist, create a SUPERFLAT world
     (default flat preset, creative, cheats on) with that seed via the world-creation API, then load it.
     Use `RefFlat` and seed `20260917`.
   - Views on the flat world at fixed coordinates (spawn XZ): `A` horizon (eye y=5 above ground,
     pitch 0), `C` straight up, `D` inside the cloud layer (y=260, pitch -20), `F` above clouds
     (y=400, pitch +45).
   - `STORM`: spawn the same storm formation as the port's STORM token (read the port's DevShot STORM
     code: formation type, offset, size, lifetime) and take S1–S5 equivalents.
   - `UNDERSTORM`: teleport under the storm cell centre on the ground, pitch -20, take 41 frames at
     250 ms (rain, sky darkness, lightning, fog over time).
   - `SHAKE`: 41 frames of normal cloud motion (same timing as the port).
   Keep the code small and obviously "reference-only". Commit locally (never push).
4. Capture: `CREATEFLAT RefFlat 20260917 A C D F`, then `STORM`, `UNDERSTORM`, `SHAKE` (separate runs
   are fine). Evidence folders under `/home/jan/simple-clouds-fabric/evidence-sym-0917-NN-ref-*`.
   Make contact sheets and LOOK at them.
5. Also record from the original's code how storms work (with file:line): where the local rain level
   is set (`WorldEffects.java:107-108` sets `level.setRainLevel(manager.getRainLevel(camera))`), what
   `MixinLevelRenderer` multiplies by the rain level (lines ~91, ~97), how lightning is spawned and
   drawn, thunder sounds, storm fog, sky/fog darkening, rain drops and rain sounds. Write this list
   into the REPORT.

**Acceptance:** reference images for A C D F, STORM, UNDERSTORM, SHAKE exist and look like a working
game (clouds visible, storm visible). **Then:** MASTER → tick Step 2 → Step 3.

## Step 3 — The same scenes in the port (26.2)

1. On the work branch add the same tokens to the port's DevShot where missing: `CREATEFLAT <name> <seed>`
   (26.2 world-creation API), the flat-world view positions identical to step 2, `UNDERSTORM`.
   Same cloud config values as the reference run (check both config files; write any difference down).
2. Run the same captures into `evidence-sym-0917-NN-port-*`. Tests must still pass.
3. Commit `DevShot: superflat reference scenes (CREATEFLAT, UNDERSTORM)`, push.

**Acceptance:** the port images exist for the same scenes. **Then:** MASTER → tick Step 3 → Step 4.

## Step 4 — Comparison report

1. For each scene make a side-by-side (`diff_pair` tool or a small Pillow script): reference | port.
2. Write into the REPORT a table: scene, what differs (cloud shape/size/altitude, colors, shadows,
   storm size/shape, rain present?, sky/fog darkening?, storm fog curtain from distance, lightning
   frequency/flash/bolt shape, thunder, motion smoothness), severity (breaks the look / noticeable /
   minor), and the likely cause with code locations (original file:line vs port file:line).
3. Known item #1 (verify, do not assume): the port never sets the local rain level
   (`ClientCloudManager`/`ServerCloudManager` set it to 0; `WorldEffects.tick` only spawns drops when
   rain > 0.02) → probably no rain drops, no sky darkening and no rain sound under storms.
4. Commit the report update, push.

**Acceptance:** every scene compared, every difference has a cause or "cause unknown" with what was
checked. **Then:** MASTER → tick Step 4 → Step 5.

## Step 5 — Fix the differences

Order: "breaks the look" first. For each difference:
1. Smallest change that follows the original's logic, adapted to 26.2 APIs.
2. A standalone test where the logic is testable without a client (like the existing `*Test.java`
   files, added to `tools/codex-check-tests.sh`).
3. Re-run the affected scenes (port) and compare with the reference images again; LOOK at them.
4. One commit per fix: `<Area>: <what now matches the original>`, push.
If a difference needs a design decision (e.g. the original looks worse than the port), write it under
"Questions for Jan" and move on.

**Acceptance:** all "breaks the look" and "noticeable" items fixed or documented as questions;
tests pass; after-images match the reference. **Then:** MASTER → tick Step 5 → Step 6.

## Step 6 — Minecraft 26.3

1. `gradle.properties`: `minecraft_version=26.3`, `fabric_version=0.160.7+26.3` (or newest `+26.3` on
   `https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml`),
   `loader_version=0.19.5` or newer; check the Loom version supports 26.3 (Fabric's
   `https://meta.fabricmc.net/v2/versions/game` and the Loom releases); `mod_version=...+26.3-fabric`.
2. `src/main/resources/fabric.mod.json`: `"minecraft": "~26.3"`.
3. DH API: `compileOnly "maven.modrinth:DistantHorizonsApi:3.0.0"` is far behind DH (API 7.x). Switch
   to the API version DH main builds (7.1.0 — step 7 produces it; use the Modrinth maven `7.0.0`
   until then) and fix compile errors in `client/dh/**`. Record every API change you had to adapt.
4. Build, fix compile errors (keep changes minimal, note each 26.2→26.3 API change in the REPORT),
   run `tools/codex-check-tests.sh`, run the dev client (`SHAKE`, `A B C D F`) and LOOK at the images.
5. Commit `Port to Minecraft 26.3`, push.

**Acceptance:** builds, tests pass, dev client renders clouds on 26.3. **Then:** MASTER → tick Step 6 →
Step 7 (DH plan), then come back for Step 7b.

## Step 7b — Dev client always with Distant Horizons (after the DH plan's Step 7)

1. Put the DH 26.3 jar from step 7 into the dev runtime so every dev client run loads it
   (`modLocalRuntime files(...)` / `modRuntimeOnly` in `build.gradle`, or `run/mods/` — prefer the
   Gradle dependency with the absolute path from the DH plan). Include what DH needs at runtime; add
   Sodium/Iris 26.3 only if DH requires them (it should not).
2. Dev client log must show DH loaded and Simple Clouds' DH compat active (search for the
   `SimpleCloudsDhCompatHandler` / `DhSupportPipeline` log lines; add one INFO line if none exists).
   No errors from `client/dh`.
3. Scenes: `B` and `D` and `STORM` with DH far terrain visible: clouds and storm fog must draw
   correctly over DH terrain (no clouds cut off at the vanilla render distance, no fog/shadow
   mismatch at the LOD border). Compare with a run where DH is disabled (DH config or remove from
   runtime for one run). LOOK at both.
4. Commit `Dev runtime: always run with Distant Horizons 26.3; DH compat checks`, push.

**Acceptance:** DH loads in every dev run, compat active, images correct. **Then:** MASTER → tick 7b → Step 8.

## Step 8 — Full test matrix on 26.3 + DH

1. `tools/codex-check-tests.sh` — all PASS.
2. Captures (all with DH): `A B C D F`, `STORM`, `UNDERSTORM`, `SHAKE`, `SHAKE FAST`, flat-world
   reference scenes. All frames present, images looked at.
3. 30-minute storm stress: `tools/claude-stress.sh 1800 evidence-sym-0917-NN-stress-storm` with
   `STORM LOOP` tokens (make the stress script accept the tokens if it does not). Record: cycles,
   failed chunks (must be 0), worst frame and every hitch > 1 s (with time), heap/direct/RSS start
   and end, transform capacity, any error. Memory must not grow steadily.
4. Same 30-minute run without storms (`A B C D F LOOP`) for comparison.
5. Write all numbers into the REPORT, commit, push.

**Acceptance:** no failed chunks, no errors, memory stable, images correct. **Then:** MASTER → tick 8 → Step 9.

## Step 9 — Final report and hand-back

1. REPORT sections: summary; per step what changed (commits); evidence folder list; before/after
   image pairs for every fix; 26.3 API changes; DH build commit + jar sha256; test and stress numbers;
   known issues; "Questions for Jan".
2. Read-only list for Jan: mods in `/home/jan/.local/share/ModrinthApp/profiles/Fabric 26.2/mods` that
   have no 26.3 version (check Modrinth: `https://api.modrinth.com/v2/project/<slug>/version?game_versions=["26.3"]`
   where the slug can be found; otherwise mark "unknown").
3. Build the release jar (`./gradlew --max-workers=8 build`), record its path + sha256 in the REPORT.
   **Do not install it.**
4. Commit + push. Tick Step 9 in the MASTER. Notify: `symbiote-notify --title "Clouds plan finished" --message "Report: simple-clouds-fabric/plans/2026-09-17-REPORT.md, ready for Claude's review"`.
5. Stop. Claude reviews everything and fixes what remains; Jan decides about installing.

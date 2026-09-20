# Simple Clouds — handoff, 2026-09-20

What is done, what is left, and the things already measured so nobody spends a day rediscovering
them. Written by Claude at the end of the 26.3 port; the work continues on branch
`work/storm-parity-26.3` in `/home/jan/simple-clouds-fabric`.

## State

The mod builds and runs on **Minecraft 26.3** with **Distant Horizons 3.3.2-dev**. Three commits,
none pushed:

| commit | what |
| --- | --- |
| `e16da58` | rain spawned at ~51% of columns instead of the original's 3% (`nextLong() % 100` keeps the sign) |
| `b8be207` | the 26.3 port itself — 68 files |
| `ce19c18` | rain animation, rain direction, vanilla snow overlap, options button crash and overlap, cloud bobbing |
| `8f0f8e6` | step 8 matrix + two 30-minute stress runs; sky flash restored to the original's behaviour |

MASTER plan steps 0–8 are ticked. Only **Step 9** is unfinished, plus the open defects below.

## Hard rules (unchanged, they matter)

- **Never touch Jan's real game or profile.** `/home/jan/.local/share/ModrinthApp/profiles/Fabric 26.2`
  is read-only to us. Never install a jar anywhere near it without Jan saying so explicitly.
- Never kill a Minecraft Jan is playing. The harness already refuses to start if it sees one.
- **No push, no merge to main.** Commit on `work/storm-parity-26.3` only.
- One heavy job at a time: a dev client and a Gradle build do not share the machine well.
- Edit Jan's Minecraft configs only while his game is closed.

## How to work here

- Build: `./gradlew build --no-daemon` (Loom 1.18.2, Java 25 temurin).
- Tests: `bash tools/codex-check-tests.sh` — 9 suites, all must pass.
- Normal world capture: `bash tools/claude-run.sh <evidence-folder> <DEVSHOT tokens>`
  (e.g. `A B C D F`, `SHAKE`, `SHAKE FAST`, `STORM`).
- Superflat capture, the one comparable to the 1.20.1 reference shots:
  `FLAT_NAME=RefFlat FLAT_SEED=20260917 FLAT_EXTRA=UNDERSTORM ./dev-relaunch-flat.sh`
- Stress: `bash tools/claude-stress.sh <seconds> <evidence-folder> <DEVSHOT tokens>`.
- Long jobs: `bg.sh start <name> -- <cmd>`, then `bg.sh list` / `bg.sh log <name>`. Do not sleep
  waiting for a client; it takes 40–120 s.
- Evidence folders are never overwritten; the scripts refuse. Name new ones `evidence-<who>-<date>-<nn>-<what>`.
- Distant Horizons: the 26.3 build lives in `run/mods/` and in
  `~/.cache/simpleclouds/dh-26.3/DistantHorizons-3.3.2-dev-26.3-d0efcc17e.jar`. Its source clone is
  `/home/jan/src/distant-horizons` on local branch `local/mony-26.3` — **never push that repo**.
- The dev client runs on **ZGC** (`build.gradle` vmArgs). Keep it: DH warns in chat that G1
  stutters, and any hitch measurement on G1 measures the collector.

## What 26.3 changed, in case you hit it again

Full list is in `plans/2026-09-17-REPORT.md` § Step 6. The four that cost the most time:

1. **The GPU layer moved** from `com.mojang.blaze3d.*` to `com.mojang.renderpearl.*` (21 classes).
   `blaze3d.vertex` still exists, so this is per class, not per package.
2. **A mixin whose descriptor no longer matches fails silently.** `LevelRenderer.render` lost
   `DeltaTracker` and `Matrix4fc` and gained a boolean; the mod loaded, logged, and rendered no
   clouds at all, with no error anywhere. When something "loads but does nothing", check the
   descriptors first.
3. **Shaders go through SPIR-V**: every user `in`/`out` needs an explicit `layout(location = N)`
   (matched by name between `.vsh` and `.fsh`), `#version 330` needs
   `#extension GL_ARB_separate_shader_objects : require`, and `#moj_import` is rejected in favour
   of `#include`.
4. **No device work while a render pass is open** — no pipeline compile, no texture fetch, no
   buffer map. Breaking this throws *"Close the existing render pass…"*, and because the cloud
   hook catches `Throwable` the pass then stays open and vanilla dies on its next command with a
   message that never mentions the mod.

## Open work, in the order I would do it

### 1. Rain stops dead at every cloud edge  ← the visible one

Vanilla's weather slot runs **before** the mod's cloud pass, the clouds are opaque, so rain is
painted over wherever a cloud covers it. Look up during a storm and the rain disappears exactly on
the cloud shapes.

**Three attempts failed; do not repeat them.** All measured:

- Cancel vanilla's slot and call `WeatherEffectRenderer.render(state, pass)` afterwards → draws
  nothing: vanilla has already cleared the state (`rainColumns=0 snowColumns=0 intensity=0.0
  radius=0`).
- Capture the columns in the mixin and draw from the copy → still nothing.
- Capture only non-empty states → still nothing. (Worth knowing: vanilla calls `render` several
  times per frame and about half the calls carry no columns — **6050 non-empty of 12400** — so an
  unconditional capture silently overwrites the good copy. Fixing that alone did not help, which
  means something besides the columns, most likely the instance buffer `prepare()` uploads, does
  not survive to the mod's draw point.)

The code and these numbers are in `SimpleCloudsRenderer.redrawsVanillaWeather` (currently returns
`false`, i.e. the attempt is off, not half-applied).

**The remaining route** is to draw the clouds in vanilla's own cloud slot instead of at the level
render tail. `MixinCloudRenderer` already intercepts exactly that point and is handed a live
`RenderPass`. The blocker is rule 4 above: `CloudsDrawPipeline.frameTransform(...)` maps a buffer,
and there are four such calls (lines ~573, 625, 704, 738). So it needs the per-draw transform
slices computed in a safe phase first and only bound inside vanilla's pass. Mesh generation and the
overlay passes (storm fog, atmospheric, shadows) can stay where they are; only the opaque and
transparent cloud draws need to move.

### 2. Step 9 — finish the hand-back

1. Assemble `plans/2026-09-17-REPORT.md` into a final section: summary, per-step changes with
   commits, evidence folder list, before/after image pairs, 26.3 API changes, DH build commit and
   jar sha256, test and stress numbers, known issues, questions for Jan.
2. **Read-only** list for Jan: mods in `/home/jan/.local/share/ModrinthApp/profiles/Fabric 26.2/mods`
   that have no 26.3 version. Check
   `https://api.modrinth.com/v2/project/<slug>/version?game_versions=["26.3"]`; mark "unknown" when
   the slug cannot be resolved. Do not change his profile.
3. Build the release jar (`./gradlew --max-workers=8 build`) and record its path and sha256 in the
   REPORT. **Do not install it.**

### 3. Cloud layer density and LOD (raised as a question, not yet decided)

Every remaining "noticeable" difference from the 1.20.1 reference — C denser, D flat blocky slabs
instead of a translucent column, F near-uniform white, the flat S2/S3/U-01 base, the S4 blob — is
one cause: the port's rewritten v2 mesh/LOD pipeline draws a denser, harder-edged layer than the
original's compute-shader generator, and it worsens with distance. Already ruled out: identical
constants, the same `clouds.fsh`, per-chunk fade-in present, the whole `shaders/program/` chain
present, config at the original's defaults. Details in REPORT § "Step 5, the remaining items".
This is tuning a rewritten renderer against reference images, with a subjective end condition —
ask Jan before opening it.

### 4. Smaller things

- **Harden the cloud hook**: `MixinLevelRenderer` catches `Throwable` and logs once. When a draw
  throws mid-pass the pass is left open and vanilla crashes with an unrelated-looking error. The
  handler should close the pass on failure.
- **Rain contrast against a dark sky** is weak — but it is weak for vanilla's own rain too, so
  check the settings before treating it as a port defect.
- `clouds.fsh` carries a **stale comment** claiming the transform always has alpha 1.0 so the
  dither never fires. Not true — `SimpleCloudsRenderer.chunkAlpha` feeds it. It cost me an hour;
  delete it when you are next in that file.

## The thing worth remembering

Jan played the 26.3 build for twenty minutes and found six defects the capture harness had missed
completely: frozen rain, rain falling sideways when looking up, clouds not bobbing with the camera,
vanilla snow over the mod's rain, an options button overlapping "Done", and that button crashing
the game. **Every one is about motion or interaction.** DevShot takes still frames from a
teleported camera; it verifies composition and nothing more. Plan a human pass in the game for
anything animated or clickable — captures alone will say everything is fine.


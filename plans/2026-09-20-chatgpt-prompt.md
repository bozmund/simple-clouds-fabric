You are taking over the Simple Clouds mod port on Jan's machine (mony). Work in
/home/jan/simple-clouds-fabric, on the existing branch work/storm-parity-26.3.

FIRST, read these two files completely before touching anything:
  plans/2026-09-20-chatgpt-handoff.md   <- what is done, what is left, and what was already
                                            tried and measured. Read it all; it will save you
                                            a day.
  plans/2026-09-17-REPORT.md            <- the detailed record, especially sections
                                            "Step 6" (every 26.2 -> 26.3 API change) and "Step 8".

State: the mod builds and runs on Minecraft 26.3 with Distant Horizons 3.3.2-dev. Commits
e16da58, b8be207, ce19c18, 8f0f8e6 are on the branch and NOT pushed. MASTER plan steps 0-8 are
ticked; Step 9 is not done.

HARD RULES - these are not negotiable:
- Never touch Jan's real game or profile. /home/jan/.local/share/ModrinthApp/profiles/Fabric 26.2
  is read-only to you. Never install any jar into it.
- Never kill a Minecraft that Jan is playing.
- Do not push, do not merge to main. Commit only on work/storm-parity-26.3.
- One heavy job at a time: a dev client and a Gradle build fight over the machine.
- Only edit Jan's Minecraft configs while his game is closed.

YOUR TASKS, in this order:

1. Finish Step 9 (the hand-back). Three parts, all described in the handoff plan:
   a) assemble the final REPORT section;
   b) a READ-ONLY list of mods in Jan's 26.2 profile that have no 26.3 version, via
      https://api.modrinth.com/v2/project/<slug>/version?game_versions=["26.3"] - mark "unknown"
      where the slug cannot be resolved, and change nothing in his profile;
   c) build the release jar and record its path and sha256 in the REPORT. Do NOT install it.

2. Fix the one visible defect left: rain stops dead at every cloud edge, because the clouds are
   drawn after vanilla's weather slot and are opaque. THREE approaches have already been tried and
   each drew nothing - the handoff plan lists them with the measurements, including that vanilla
   calls WeatherEffectRenderer.render several times per frame and about half the calls carry no
   columns (6050 non-empty of 12400). Do not repeat those three. The remaining route is to draw
   the clouds in vanilla's own cloud slot, which requires computing the per-draw transform slices
   BEFORE the render pass opens, because 26.3 forbids mapping a buffer while a pass is open.

3. Only if Jan agrees: the cloud-layer density/LOD parity question from Step 5. It is tuning a
   rewritten renderer against reference images with a subjective end condition, so ask first.

HOW TO VERIFY YOUR WORK - this part matters more than usual:
- Build: ./gradlew build --no-daemon. Tests: bash tools/codex-check-tests.sh (9 suites, all must
  pass).
- Captures: bash tools/claude-run.sh <evidence-folder> <DEVSHOT tokens>, or for the superflat
  scenes that are comparable to the 1.20.1 reference:
  FLAT_NAME=RefFlat FLAT_SEED=20260917 FLAT_EXTRA=UNDERSTORM ./dev-relaunch-flat.sh
- Use bg.sh start/list/log for anything long. A dev client takes 40-120 s; do not sleep-poll it.
- LOOK at the images you capture. Do not report a fix as working because it compiled.
- The capture harness takes still frames from a teleported camera. It CANNOT see anything about
  motion or interaction. Jan found six defects in twenty minutes that it had missed entirely -
  frozen rain, rain falling sideways when looking up, clouds not bobbing with the camera, vanilla
  snow over the mod's rain, an options button overlapping "Done", and that button crashing the
  game. For anything animated or clickable, ask Jan to look in the game.
- If a mod "loads and logs but does nothing", check the mixin descriptors first: in 26.3 a mixin
  whose target signature no longer matches fails SILENTLY.

REPORTING:
- Tell Jan what you actually measured, not what you expect. If a fix fails, say so and say why.
- If you break something that was working, revert to the working state rather than leaving him
  with the broken one, and write down what you learned.
- Keep notes in plans/2026-09-17-REPORT.md and tick MASTER steps as you complete them.


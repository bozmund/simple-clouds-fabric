# Simple Clouds continuation — 2026-09-15, 09:15–09:38 CEST

Candidate: `/home/jan/.cache/simpleclouds/port-candidate-tgkFnnmU`.
Read this after the Claude handoff. No Git mutation or real-profile installation.
Accepted scope is saved in `plans/2026-09-15-continue-claude-handoff.md`.

## Changes

- `DevShot.java`: monotonic 90-second timeout and 3-second minimum settle;
  ground probes projected through the actual camera render-state matrices;
  require compiled visible ground only inside the view and terrain render
  distance. No-ground-in-view fallback also requires actual ground samples,
  loaded nearby chunks, completed sections and empty compile queue.
- Synchronize each test teleport (and resolved S2 ground height) on the
  integrated server thread via `ServerPlayer.connection.teleport`. The old
  client-only pin could outrun the server's chunk delivery. Preserve client pin.
- Require two fresh cloud batches, 97% field population and finished transition
  before accepting a screenshot. `SimpleCloudsRenderer.isCaptureFieldSettled`
  is only a read-only accessor used by the dev harness; no production rendering
  algorithm or shader changed in this continuation.
- New tools: `codex-check-tests.sh`, `flash-metrics.py`, `evidence-metrics.sh`.
  Brightness metrics use a fixed terrain ROI (10–90% width, 80–98% height).
- Backups: `DevShot.before-codex-20260915.java` and
  `SimpleCloudsRenderer.before-codex-20260915.java` at candidate root.

## Evidence and reassessment

1. `evidence-codex-0915-gate`: initial frustum-only approach produced 64
   captures but S5-01 lacked terrain. Zero probes were incorrectly treated as
   sky-only when chunks had not supplied height data. NOT a visual pass.
2. `evidence-codex-0915-server-view`: authoritative teleport + actual render
   matrices + nonempty ground samples. 64/64, S5-01 has terrain; no refused
   view or render-log error. S5 probes 172/194. Viewed S5 at full resolution.
3. `evidence-codex-0915-depth`: STORM FOGDEBUG, 64/64. Viewed S1/S4 at full
   resolution: sky blue and terrain/cloud surfaces grey, consistent with
   Claude's <=1e-7 cleared-depth threshold. Not a raw depth-value dump.
4. `evidence-codex-0915-no-fog`: STORM NOFOG, 64/64. Inspected all contact-sheet
   frames. Forced 200/1500/3000/8000-block strikes logged; near strikes explicitly
   allow sky flash. Terrain ROI mean 169.125–169.190/255; maximum adjacent-frame
   mean absolute pixel difference 0.039/255. Whole-terrain flash not reproduced.
   S4 mean 202.7, std 36.1, >250 RGB fraction .077: not uniformly white.
5. `evidence-codex-0915-hide-flash`: STORM NOFOG HIDEFLASH, 64/64. Contact-sheet
   review exposed empty clouds in S5-01..05, despite loaded terrain. This was
   NOT accepted as cloud-settle evidence; led to fresh-batch/transition gate.
   S4 mean 215.8, std 35.7, white fraction .280.
6. `evidence-codex-0915-settled`: final gate, STORM, 64/64 in 86 seconds.
   Entire contact sheet inspected: S5 has both terrain and clouds from frame 1;
   S1–S4 captured, no refused views, no render-log errors, failed chunks=0.
   Transform capacity max 64, reported transformWritesPeakPerFrame max 326;
   sampled RSS up to 4.57 GB, direct 81 MB. Worst reported frame 473 ms during
   the run: do NOT claim smooth frame-time/performance acceptance.
   S4 mean 229.3, std 45.6, white fraction .686: NOT uniformly white, but very
   bright cloud tops remain and are not declared visually correct.

The original 100%-white S4 was not reproduced. Shader inspection shows high
storm voxels reach brightness 1 and cubeNormals defaults off; this can create
large flat-white cloud tops. This is a hypothesis for overbrightness, NOT proof
of the old all-white failure's cause. Do not darken arbitrarily without reference.

`NOFLASH` disables fog bolt light only. Use `HIDEFLASH` for the user-facing
Hide Lightning Flashes option; it gates sky flash too. Saved dev options still
report hideLightningFlashes:false after tests. Real-profile settings untouched.

## Build and tests

Final `./gradlew --no-daemon build --console=plain`: success.
All seven standalone tests rerun and pass. Logs:
`evidence-codex-0915-settled/build.log`, `standalone-tests.log`.
Gradle deprecation warning remains; Gradle's own unit-test task has no tests.

Built JAR SHA256:
`6c1f210d9248659facbfd5a572d9011d03dbad0238474a7c00c8e48b9361dc4a`.
NOT installed. Installed JAR should remain `1fa5ff98...`; verify before install.
Every development run stopped only the `simpleclouds-devclient` unit on exit.

## Remaining (not claimed finished)

- Overbright S4 cloud tops: compare original lighting/normal configuration;
  old completely-white symptom remains unconfirmed, not proven permanently fixed.
- Fog horizon dark band and 64-block vertical streaks remain visible with fog;
  absent in NOFOG. Need controlled spatial fog tuning against the original.
- Re-run NOFOG/HIDEFLASH/depth and SHAKE FAST/A B C D F after the final gate;
  those comparative runs above predate the final cloud-settle addition.
- Validate transition depth consistency (main uses incoming scene depth while
  color blends both scenes), relevant to transient cloud/sky classification.
- Full 30-minute stress and real-profile visual validation not performed.
- Do not use candidate `dev-relaunch.sh --install` (wrong relative destination).

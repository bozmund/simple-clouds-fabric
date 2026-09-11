# HANDOFF — Simple Clouds port (2026-09-11, from Claude)

Read all of this before doing anything else.

## 1. The "no clouds" bug is FIXED — do not re-investigate it

**Root cause:** `CloudsDrawPipeline` never declared the `DynamicTransforms` and
`Projection` uniform blocks that `clouds.vsh` imports from vanilla. In 26.2,
`GlProgram` skips any uniform block the pipeline did not declare (log line:
`Found unknown and unsupported uniform DynamicTransforms`), so
`pass.setUniform("DynamicTransforms", ...)` did nothing. `ModelViewMat` was read
from a random buffer, which gave garbage vertex positions and no visible clouds.
That warning was in every log this morning.

**Fix (already applied, verified on screen, do NOT revert):**
- `RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)`, which declares Globals,
  DynamicTransforms, Projection and Fog, exactly like vanilla `RenderPipelines.CLOUDS`.
- `RenderSystem.getDynamicUniforms().writeTransform(view)` instead of `new DynamicUniforms()`.
- The red debug colour in `clouds.fsh` is removed. White clouds now render overhead.
- The fixed jar is installed in the real profile `mods/` (12:06).

**Your earlier theory was wrong:** the unbound `BayerMatrixSampler` did NOT cause the
missing clouds. Removing the dithering was a misdiagnosis. Task 1 below restores it properly.

## 2. Rules (these are what went wrong this morning)

1. **Test every change with `./dev-relaunch.sh`** (in this folder). It stops stale
   clients, launches exactly ONE dev client, waits for the first draw, checks the log
   and saves a screenshot to `/tmp/sc-latest.png`. Never start clients another way:
   a 7-hour-old client left over from the night made the whole morning's tests meaningless.
2. **`first draw, N instances` proves nothing.** It printed all morning while nothing
   was visible. After PASS you must **read `/tmp/sc-latest.png`** and check that
   what you changed is actually on screen.
3. **Treat `unsupported uniform` as a hard failure.** Every uniform block and sampler a
   shader uses must be declared on the pipeline (`BindGroupLayout.withUniform` /
   `withSampler`) AND bound in `draw()`. The script fails on it.
4. Look up 26.2 APIs with `javap` on the Minecraft jar instead of guessing:
   `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-043a8b3edf/26.2/minecraft-merged-043a8b3edf-26.2.jar`
   (JDK: `/nix/store/z2jmdp15lphyzy2pgfyqxv1gybcjy7n1-temurin-bin-25.0.3/bin/javap`).
5. When a task is verified on screen, run `./dev-relaunch.sh --install` so the
   real profile gets the new jar, and tick the task off in `PORTING.md`.
6. **Do not ask the user questions and do not stop.** Work through the task list in
   order until it is done. If something is blocked, write down why in `PORTING.md`
   and move to the next task.

## 3. Tasks, in order

1. **Restore edge dithering properly.** Declare `withSampler("BayerMatrixSampler")` in the
   pipeline's bind group layout, create the Bayer matrix texture (see how the original
   mod builds it in `/tmp/simple-clouds-src`), bind it in `draw()` (find the 26.2
   `RenderPass` texture/sampler binding method with javap), and restore the dither
   `discard` in `clouds.fsh`. Verify: dithered cloud edges in the screenshot, no warnings.
2. **Real cloud heights and layers.** Remove the test setup in `SimpleCloudsRenderer`:
   `defaultLayers()` (heightOffset 40) and `layerBase = camGridY + 20` (clouds
   *follow the camera*). Drive the layers from the ported config/data, and fix
   `CloudSpawningDataManager: Could not find root Simple Clouds config`. Verify: clouds
   stay fixed in the world when the player moves up or down.
3. Then the remaining stubs from `PORTING.md`, in this order: transparency, storm
   fog, shadow map, world effects/rain, config screens, commands, 3D previewer, DH support.

# Simple Clouds: remaining API and effects audit

Execution evidence for accepted C09/C10, not a replacement plan. Inspected 2026-10-01 on Mony. Goal remains active.

## API inventory

### C10 follow-up: first integrated runtime and ownership acceptance

Later actual biome predicate/fog-disable fixture `purestorm-NtT8bP` passes81frames. It checks loaded solid-ground shelter (not an arbitrary void position), local wet/dry columns despite global camera weather, root disable and optional storm-fog disable. Shared biome ambient wind and particle storm-strength adapters are now added; their runtime check is in progress, not yet blanket wind/biome parity acceptance.

### C09 follow-up: CloudImageRenderer original GPU image API

Replaced public image stub with independent2048 RenderPearl target, original caller-supplied GPU mesh buffers, original isometric transform, background/rotation/zoom and safe PNG export. No window resizing or ownership transfer of caller generator. Immediate close after async export defers GPU resource release safely; callback stays on client thread. New actual isolated probe checks2048 PNG, original background, generated geometry, unchanged window, released renderer target and still-live caller generator. `preview-p5MUvL` passes12captures and all existing editor/menu/PNG gates; log SHA256 `4cc669008f89e35dab24b10b072814084ba535b930f75e0ba6a21bd2b7916b33`.

Initial one-shot exports found a genuine first-frame revealage clear issue, not a missing encoder: GPU diagnostics show raw clear and drawn background26,51,76,255 but resolved0,0,0,255. Installed26.3 GlCommandEncoder.createRenderPass bytecode clears attachments before glDrawBuffers, so a new MRT FBO does not clear attachment1 yet. Replaced combined MRT clears with explicit per-texture clears; same cold first-frame image now resolves26,51,76,255 and PNG passes. Transient GL diagnostics removed; retain original failure evidence. Legacy public blur/composite/depth stage APIs still need explicit disposition/adaptation; image API row below is a historical inventory superseded by this verified follow-up.

Development variant `-PintegratedWeather=true` uses pinned source-built upstream JARs and includes their notices; default remains false while further effects tests are open. Build helper verifies source archive hashes, refuses unsafe archives/files, uses bounded owned build services and does not touch production mods. Repeated upstream builds produced identical JAR hashes. Local artifact-only exclusive Ivy repository supplies module identities required by installed Loom1.18.2; its source `NestableJarGenerationTask.from` explains the first bare-file/no-capability packaging failure. No binary patch or network workaround.

Shared LocalWeatherEffects calls the original CloudManager rain calculation and wind direction; bounded RecentWetColumns covers local AFTER_WEATHER history. Optional adapters wrap the three upstream eligibility sites without removing their roof/biome/collision rules, align upstream wind, and add root precipitation owner/budget/biome-effect controls. Actual fixture `purestorm-lfcqvP` exercises the upstream CustomParticle constructor and WindManager native registration: wet retention, dry-boundary removal, original-owner handoff, zero-budget removal, unrelated vanilla preservation and reference-only disconnect cleanup all pass,81frames,exit0. OwnedWeatherParticles never clears the entire Minecraft particle engine. Startup smoke `purestorm-tluzCq` and deterministic helpers also pass.

Preserved failure `purestorm-hj4On8` is a fixture counter assumption: an ORIGINAL handoff removes all previously spawned upstream-owned effects, not only the newly constructed fixture particle. Adjusted expectation to empty owned registry/upstream counter and added a real unrelated vanilla particle preservation check. Do not label that failure as a render/Mixin crash.

Still OPEN: actual biome storm effects/fog/audio/wind matrix, reload/rejoin and sustained fullpack stability, API legacy disposition, and complete release/source/relinking handoff. OpenAL cannot open a device in isolated launches, so successful particle runs do not prove audible sound behavior. No permanent production install, Git commit or push.

| Path | Live reachability / disposition | Remaining requirement |
| --- | --- | --- |
| Renderer.fillReport | MixinMinecraft invokes it when vanilla assembles a crash report. Restored pipeline/reload/fog/export state plus original mesh report. Actual fixture crash reports 12.47.32 and 12.49.28 contain both categories. | Preserve this evidence; no extra deliberate production crash. |
| DefaultPipeline.prepare/afterLevel | Intentionally empty in original; current afterSky owns geometry/composition and beforeWeather owns screen-space fog. | Do not invent missing default work merely from an empty method. |
| ShaderSupportPipeline.prepare/afterSky/beforeWeather | Original late-stage ownership. Current afterLevel dispatches renderCloudsAfterShaderLevel. | Supported shader/DH runtime matrix still required; method routing alone is not parity. |
| CloudImageRenderer | No repository callers outside its own class. Silent public legacy stub; actual editor uses original GPU preview and CloudPreviewExport. | Decide/document external API support or replace with a functioning adapter; do not describe stub as working. |
| doBlurPostProcessing / doFinalCompositePass / doStormPostProcessing / doCloudShadowProcessing | Only declarations in current tree; live pipeline performs its stages directly. These public legacy methods are still empty. | Audit custom-pipeline callers and add appropriate adapters or explicit API migration. Calling a whole-frame draw from each adapter would duplicate composition. |
| copyDepthFromCloudsToMain/MainToClouds/CloudsToTransparency | Only legacy declarations; RenderPipeline backend copies depth inside CloudsDrawPipeline. | Raw original framebuffer semantics cannot be assumed equivalent; external API disposition remains open. |
| Client presets / server snapshot lifetime | Built canonical resources into client assets, separate client reload registration. Menu-only runtime confirms eight local presets after real disconnect clears server state. | Resource reload/reopen sustained checks remain. |

## Verified editor/runtime evidence

- `evidence-codex-20260930-isolated-motion-preview-zb7Wys`, exit0, twelve captures and required JSON/PNG/menu gates. Menu has no ClientLevel or integrated server. Actual PNG contains 128100 clear and 281820 covered pixels, 854x480, after rotation/zoom/resize.
- Latest log SHA256: `97a3352fd4da754395f0b7899131e56774e322a7fced227839f667a25771bee6`.
- Original Screenshot.takeScreenshot forces opaque alpha in pinned Minecraft; current exporter uses raw RenderPearl RGBA readback. Pure channel/alpha/flip test and actual alpha coverage both pass.
- Menu test now waits for the final TitleScreen after asynchronous saved disconnect. Expected export-result Popup is allowed and verified rather than misclassified as an unexpected replacement screen. Retain failed fixture logs OFgFzb/0MFBPp; they are not production correctness evidence.
- Post-editor two-client sync/reload/reconnect regression `evidence-codex-20261001-dedicated-multiplayer-1aeZsV` exit0. Server log SHA256 `c1531444e3e0ce1f6f4f0cd9774325f39f20e863669c4cd50261e2ffa4cc4e03`.
- Candidate SHA256 `0e48eb58749dd4c723ef856bcb56aff57f59568fc37e185987489bd83a9ca354`. Development only; user's installed JAR unchanged. Jan owns final visual acceptance.

## C10 upstream inspection

### Follow-up: correct 26.3 branch and actual build

- Branch discovery found Immersive Storms26.3.x, commit `157767f062f464678f96af3c104def5c1f721ac3`. This supersedes using26.1.x as the implementation source; the older inspection below remains historical. Source archive SHA256 `706ac420a2dfec3c535d988303d8c6df7eed91e502bd1f1b8294b71aded08107`. Dependencies match existing26.3 binary. Primary Maven probes for DH API7.0.0 and YACL3.9.7+26.3-fabric returned HTTP200; do not repeat obsolete claims that those versions are unavailable.
- Particle Rain pinned source archive SHA256 `68b0b8d318d42c34b934c8500dec5ddc4536c29bb70d6f5a91ca11f9e8309f97`. Actual upstream `:26.3-fabric:build --configure-on-demand` passes, `/tmp/simpleclouds-upstream-particle-target.log`, memory peak1.3G. Initial normal configuration started resolving irrelevant1.20.1; stopped ONLY the owned upstream build unit and reran targeted configuration. Upstream builds are bounded separate owned systemd units, not persistent AI work.
- Build success alone does not integrate effects. Source archives and built library remain in `build/upstream-inspection`; user's profile unchanged. Reproducible vendoring/module composition and local weather/fog/wind/ownership adapters remain open.

## DH/Iris exact failure and scoped fix

- With current candidate + full pack + ComplementaryReimagined5.9.3 + DH3.3.4, run PZvaS0 failed because DH's render task queue sent a chat message inside the shader shadow pass. Lazy bitmap glyph upload called copyBufferToTexture while a render pass was open. Full log SHA256 `5bc30a64ea147ea1be9bf6221342010020e1f6f1267acababcd3ae434c6c9057`.
- Exact no-Simple-Clouds baseline D1NWKW reproduces the same DH/Sodium/Iris/FontTexture stack, SHA256 `e714328068ae83b6bdf25a5ccd07b502a60979f1d9b6b347980ed227ada2c758`. Therefore it is not evidence of a Simple Clouds draw ordering defect. Baseline restored installed JAR SHA d77f0af... and exact Iris/options; output reproduction exit0 is diagnostic success, NOT compatibility acceptance.
- Added optional Pseudo MixinDhChatDispatch for DH sendChatMessage(String), preserving original translatable message semantics. Dispatch bounded to256 entries, drains16/client tick, requires original level/player identity, does not use Minecraft.execute (may run immediately). Overflow explicitly logs discarded oldest diagnostic. No DH JAR edit, module disable or render-pass closure hack.
- BoundedDeferredQueueTest passes FIFO/null/overflow/isolation and8000 concurrent offers; entire helper suite passes. Actual full pack GRIDSTORM-7ChO35 with same shader and DH passes81 frames/error gate; log confirms `[DH-CHAT] delivered on client tick` and original GPU initialized. Log SHA256 `db7706e3760948693c2e59ea6876a73c3d6733bfb725f20ec0fe318a1455fe24`. Candidate SHA256 `8aa815a975358cbb46f7c04cdce6c0bd51678fa4bf60d6c2d1e82e355e8139e7`, not installed permanently.
- This confirms the originally reproduced DH/font render-pass symptom for this scenario, not long-session/shader/VR parity. Optional adapter with DH absent still needs actual runtime regression. Other unrelated Voice Chat microphone/off-thread warning remains separate.

Pinned source revisions inspected (no code bundled yet):

- Particle Rain `ff0e693a11a38fad454be72527ef605484e61c69`, https://github.com/PigCart/particle-rain/tree/ff0e693a11a38fad454be72527ef605484e61c69 . MIT, Stonecutter source preprocessing, declared 4.0.0-beta.100, 26.3 Fabric variant depends on Fabric API0.161.0, loader0.19.5, optional Iris1.11.6. Do not copy inactive version-comment branches without preprocessing.
- Immersive Storms `8e1193736698fd406a53023d2c6e921747df07ae`, https://github.com/TheDeathlyCow/immersive-storms/tree/8e1193736698fd406a53023d2c6e921747df07ae . LGPL-3.0. Repository branch name26.1.x alone is not proof that available binaries only support26.1.
- Existing profile has disabled `immersive-storms-1.8.0+26.3.jar.disabled`. Embedded fabric.mod.json declares MC~26.3-, Java25, loader>=0.19.5, Fabric API>=0.161.0 and YACL>=3.9.7. This binary is not enabled or bundled by this audit. Current development Fabric API is0.160.7; resolve dependencies explicitly before integration, no silent metadata override.

### Concrete integration risks and next checks

- ParticleSpawner sky/surface/block effects select `data.weather.isCurrent(level)` and camera-global rain/thunder levels. WeatherDataMixin watches setRaining, not local storm boundaries. Every actual spawn site needs local Simple Clouds coverage/intensity and lifecycle handling; flipping global weather would break server authority and still leak particles across boundaries.
- Immersive Storms WeatherEffects.isWeatherAffected uses Level.isRaining before biome, roof and surface checks. Adapt the local-weather input while retaining biome tags, heightmap and sky-access rules. Current Simple Clouds Level.isRainingAt hook alone does not replace this global predicate.
- Standalone Particle Rain already suppresses Simple Clouds custom rain through renderCustomRain. Built-in effects must use explicit owner selection and avoid duplicate initialization when standalone upstream is present. Rain audio currently has a separate config predicate; audio ownership must be checked too.
- Preserve upstream licenses/notices and corresponding source. Prefer separable modules over indiscriminately merging LGPL classes into the base licensed mod. This is an engineering audit, not legal clearance for public redistribution. No public publication authorized.
- Required acceptance stays: correct local storms/biomes/roofs, settings and budgets, reload/disconnect cleanup, one rain/fog/audio owner, independent fallback and full-pack performance. None is claimed completed by source inspection.

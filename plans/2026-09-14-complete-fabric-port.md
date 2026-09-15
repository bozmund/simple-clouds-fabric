# Complete the Fabric 26.2 Simple Clouds Port

## Summary

Finish every remaining original-mod feature while first fixing the active visual regression: cloud shaking caused by broad cache invalidation and regeneration waves.

The current CPU renderer remains the trusted baseline. The GPU compute generator will be restored as a second backend for the RTX 5090, with an automatic CPU fallback for unsupported devices. The existing uncommitted storm-fog experiment is preserved and assessed separately; it must not be mixed into the shaking fix or committed without visual proof.

## Phase 0 — Preserve evidence and establish a trustworthy baseline

- Do not reset, discard, commit, or install the current dirty working tree. Record its diff, build status, affected shaders, and screenshots as an experimental storm-fog candidate.
- Rebuild the last known-good committed version and capture the standard views:
  - A–E cloud views: horizon, landscape, up, above-cloud, timed motion.
  - S1–S5 storm views: edge, under-cloud, side, above, timed lightning.
- Read every captured image and write only observed differences against the original/reference images.
- Keep the real Modrinth profile untouched until a dev-client candidate passes its exact visual test.

## Phase 1 — Fix cloud shaking and regeneration storms

- Treat the current `sigTick = 1.0F` change as an unproven experiment, not a fix. Remove its diagnostic spam before final validation; do not claim success from a build alone.
- Replace the global `regionSignature` invalidation rule:
  - A small change in one cloud formation must not mark every LOD chunk stale.
  - Give each generated chunk a local generation key based on its LOD, config/type data, and only the formations whose bounds affect that chunk.
  - Quantize local formation transforms at the chunk’s actual sampling resolution so insignificant movement does not schedule regeneration.
  - Invalidate only intersecting chunks when a formation grows, expires, moves across a meaningful boundary, or changes type.
- Retain continuous per-chunk scroll offset between mesh generations. Regenerate shape/mask changes in shared snapped phases so neighboring chunks swap coherently and cannot create vertical seams.
- Replace per-chunk INFO logging with a bounded 30-second summary: stale reason counts, queued/completed chunks, generation time, upload bytes, and worst frame time.
- Prove the result with a 10-second, 0.25-second-frame motion sequence at normal and 32× wind: no jump, no visible seam, no whole-field regeneration wave, and no growing log file.

## Phase 2 — Finish storm-cloud fidelity

- Evaluate the current uncommitted shadow-map/raymarched storm-fog candidate in isolation. Keep it only if it passes S1–S5 without shader/uniform warnings, excessive GPU allocation, or loss of cloud geometry.
- Complete the original’s storm fog behavior:
  - cloud-shaped fog using storm depth/coverage rather than a flat opaque overlay;
  - local per-bolt fog lighting, controlled by `stormFogLightningFlashes`;
  - no global brightness change from distant lightning;
  - Hide Lightning Flashes suppresses both sky and fog flashes;
  - preserve the existing density hard cap as a safe fallback.
- Recheck storm-cloud chunk borders, LOD transitions, transparent/opaque overlap, depth sorting against Distant Horizons, and distant-fragment fog fade.
- Validate S1–S5 in the real profile, including nearby and distant lightning, storm rain, thunder delay/attenuation, and memory/FPS sampling.

## Phase 3 — Restore the GPU compute generator

- Audit the original compute pipeline, buffer formats, dispatch dimensions, synchronization, and shader inputs before changing the established CPU path.
- Add a selectable GPU backend using the original compute mesh logic, persistent GPU buffers, explicit barriers/fences, bounded dispatch/upload work, and no per-frame allocation.
- Detect required GPU compute support at runtime. Prefer the GPU backend on the RTX 5090; automatically retain the CPU backend on unsupported hardware or after a GPU initialization/runtime failure.
- Keep CPU and GPU output comparable for the same seed, formation state, LOD, scroll, transparency, shadows, and storm views. Add backend identity and timing to the debug/diagnostic summary.
- Stress-test both backends for 30 minutes with the full field and storm scenes. There must be no VRAM growth trend, Java/direct-memory leak, render-thread stall, or device-loss crash.

## Phase 4 — Complete all remaining original features

- Port all fog render modes and make the existing configuration select real behavior.
- Port the offscreen previewer image export, debug overlay renderer, custom rain-sound replacement, and water-colour modulation.
- Restore the server `/clouds` command tree using a Fabric 26.2-safe bootstrap/registration strategy or manually validated string arguments where typed original arguments cannot be registered safely.
- Test server commands, persistence, sync, respawn/dimension changes, whitelist/blacklist behavior, client commands, and a multiplayer client connection.
- Recheck each feature in the original feature-parity table and mark it verified only after its actual on-screen or integration test.

## Validation and delivery

- Every rendering change uses `./dev-relaunch.sh`; inspect its screenshot and treat shader/uniform warnings as failures.
- Before real-profile installation, require clean build, no runtime errors, stable memory, and the relevant A–E/S1–S5 evidence.
- Install only after the game is closed. Re-run the same evidence set in the real Modrinth profile with Distant Horizons.
- Update `PORTING.md` and `VISUAL-PARITY-RESULT.md` with honest before/after evidence, unresolved deviations, backend performance, and image paths.
- Preserve current uncommitted work throughout. Commit only a bounded, verified phase and only after Jan explicitly approves that exact commit; never reset, rebase, or overwrite unrelated work.

## Assumptions

- Target is the Fabric/Minecraft 26.2 port in Jan’s existing Modrinth profile.
- Visual parity with the 1.20.1 original, real-profile stability, and full original feature coverage are all required.
- GPU compute is required on the RTX 5090, but CPU rendering remains a supported fallback.

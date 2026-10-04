# Simple Clouds 26.3 — accepted remaining-work execution goal

Accepted instruction from Jan, 2026-10-01: "kreni stavi si novi goal i kreni raditi na tome dok nisi gotov sa svakim stepom koji si pokazao".

Full governing accepted scope remains `plans/2026-09-27-MASTER-PORT-PLAN.md`, including its checkpoints and Jan's adjustment assigning final visual gameplay review to him. This execution record does not replace it or revive superseded CPU fallback / visual comparison requirements.

## Scope change — external shaders removed, 2026-10-01

Jan requested removal of shaders because they will not be used in the final modpack. Removed Iris 1.11.6, miniature-shader 2.19, Complementary Reimagined 5.9.3, Complementary Unbound 5.9.3 and Iris configuration from the Fabric 26.3 profile, recoverably, to `/home/jan/.cache/simpleclouds/shader-removal-20261001-c2mQIF`; checksums are retained there. Simple Clouds' own GPU shader programs and Sodium remain: those are renderer components, not external shader packs. Further release validation uses the shader-free profile. Historical shader test evidence remains valid but external shader compatibility is no longer required for this release.

## Goal

Finish the remaining accepted work packages, starting with collective sleeping and storm removal. Close each only after its appropriate deterministic and actual functional checks pass; record evidence and uncertainty in the master plan. Do not claim whole-port completion from a narrower check.

## Work packages, in execution order

1. Collective sleep/storm authority: daytime thunder-cloud sleep, preserved vanilla bed restrictions, insufficient sleeping-player percentage, successful collective deep sleep, nearby storm removal and synchronization to all clients; quitting a bed/disconnecting alone must not clear storms.
2. Server reload and client lifetime: datapack/cloud-type changes, join/rejoin/disconnect cleanup, dimension/travel resynchronization and stale data rejection.
3. Server/API lifecycle and compatibility audit: finish identified omissions against original behavior; retain verified renderer/networking work rather than introducing parallel replacements.
4. Editor/preview/save/load/export workflow: actual functional interaction and persisted content, GPU preview and resource lifetime.
5. DH compatibility and sustained full-profile stability/performance without external shaders: separate independent dependency warnings from port regressions, preserve Intel rendering, use declared memory limits and safe isolated processes.
6. Approved Particle Rain and Immersive Storms integration: honor source licensing/credits and the already accepted unified weather-effects scope; do not silently substitute external installed mods for implementation.
7. Final deterministic/build/runtime validation and release handoff: exact artifact hash, evidence index, known limits, rollback and tested deployment. Git operations and public publishing still require Jan's explicit authorization for the exact operation.

## Safeguards

- Existing user worlds/profile and unrelated changes remain intact. Fixtures use only named scratch directories/worlds and owned test units.
- Dedicated test EULA is already explicitly accepted by Jan for a localhost-only disposable test; no public port or permanent server is authorized.
- Preserve model services and remote access. Stop only owned test processes; run memory preflight/guards.
- No automatic Git mutations. Do not overwrite concurrent changes.
- Reassess after two similar failures; consult pinned source/documentation before retrying.
- Persist checkpoint results and resume naturally across goal turns. Ask only for actual missing authority, consequential choices or external prerequisites.
- Final visual review belongs to Jan during gameplay; functional GPU/weather/network/lifecycle/crash/performance checks remain agent work.

## Starting evidence

`evidence-codex-20261001-dedicated-multiplayer-hjKZrY` passed actual two-client config/clock/rain/movement, disconnect/rejoin and saved server shutdown. It does not establish collective sleep, complete storm/weather parity, editor workflow or all remaining packages.

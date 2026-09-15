# Fabric port implementation checkpoint — 2026-09-14

Status: implementation and validation in progress, NOT release-ready. No commit,
push, or real-profile jar installation performed.

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

RTX 5090 inspection showed ~29.8 GiB occupied by an existing workload; no model or
AI service was stopped. Dev launch environment currently selects Intel Vulkan.

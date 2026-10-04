# C11 GPU allocation optimization checkpoint — 2026-10-01

User priority: improve execution efficiency rather than reduce cloud mesh refresh cadence. Installed profile remains b1676cd704db0d50aca61eb744303ac666172487df39c01e00ada5f6a41ac396 (STATIC5). The prototype below is source-only, not installed, and does not change refresh settings.

## Implemented prototype

original_cube_mesh.comp reserves opaque face slots once per cube instead of once per emitted face. Original noise, neighbor visibility conditions, face order, brightness, LOD, transparency and position calculations remain unchanged. Both packed and fixed-section layouts are covered. Baseline source is test-fixtures/original_cube_mesh-allocation-baseline.comp.

## Verified correctness

tools/check-mesh-allocation-parity.sh exercises real Intel OpenGL compute. Exact full-record multiset comparison passed 768 combinations, including both cloud types/styles, transparency, packed/fixed layouts, scales, offsets, culling and boundary overrides: 65,832 opaque and 18,080 transparent records. GL errors are checked throughout. Valid log: /tmp/simpleclouds-mesh-allocation-parity-valid-20261001.log.

Earlier fixture runs were rejected: two integer uniforms were accidentally set using floating-point setters; the corrected fixture passes without GL errors.

## Performance measurement and limitations

tools/benchmark-mesh-allocation.sh alternates baseline/prototype on identical 32-cubed input, with eight warmups and 32 measured samples each. Mesa timestamp and elapsed queries returned implausible sub-microsecond values, so those are NOT accepted as GPU timings. The revised benchmark records synchronous CPU wall duration of dispatch plus glFinish; synchronization exists only in this test, never production.

First three wall-time runs show dense-input mean ratios baseline/prototype 1.0353, 1.0763, 1.0443 and sparse-input ratios 1.0085, 1.0135, 1.0299. This suggests a small dispatch-path improvement, not a measured game FPS improvement. Logs: /tmp/simpleclouds-mesh-allocation-wall-benchmark-20261001.log and corresponding -run2/-run3 logs. Noise computation is not optimized by this prototype. No claim about frame pacing, game appearance or production performance is made.

## Next discriminating work

Validate production-sized layouts and actual full-pack frame timings at exactly the same cadence before deploying. Investigate shared neighbor-noise reuse separately, preserving region boundaries, barriers, transparent gradients and bit-exact output; do not substitute slower mesh refresh for this optimization. Continue the accepted master plan; C10/C11/C12 remain open.

No Git mutations, user data deletion, model shutdown or profile installation performed for this experiment.

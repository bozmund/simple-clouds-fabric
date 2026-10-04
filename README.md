# Simple Clouds — Fabric 26.3 port

Unofficial port of [Simple Clouds](https://github.com/nonamecrackers2/simple-clouds) by
**nonamecrackers2** to Minecraft 26.3 on Fabric, made for a personal modpack. It is not
affiliated with or endorsed by the original author; all credit for the mod goes to them.

**Status: work in progress.** Functional, networking, integrated-weather and lifecycle
tests are recorded in the master plan; performance/release acceptance is not complete.
Jan performs final visual review during gameplay. No public release is published.

- `plans/2026-09-27-MASTER-PORT-PLAN.md` — accepted scope and authoritative evidence
- `plans/2026-10-01-REMAINING-PORT-EXECUTION.md` — scope adjustments and remaining work
- `THIRD-PARTY-NOTICES.md`, `WEATHER-SOURCE-AND-RELINKING.md` — module sources/licenses/rebuild
- `tools/prepare-private-handoff.sh` — private candidate/source package; does not install or publish

Older implementation history (not the current acceptance checklist):

- `plans-claude-handoff.md` — current state, rules and next steps (read first)
- `PORT-PROGRESS-2026-09-14.md` — dated evidence log of every test run
- `PORTING.md`, `VISUAL-PARITY-*.md`, `STORM-PLAN.md` — how the port was done
- `dev-relaunch.sh`, `tools/` — isolated dev-client test runs and image metrics

## Transparency performance

On capable OpenGL4.0 backends the renderer uses the original weighted transparency
equations in one indexed-blend MRT draw instead of drawing the same cubes twice.
The original two-pass renderer remains the fallback where indexed blending is not
supported. Geometry, noise, resolution and generation cadence are unchanged.
Actual activation is logged as `[OIT-MRT]`; backend selection is `[OIT-BACKEND]`.

For controlled A/B tests only, `SIMPLECLOUDS_DEV=1 SIMPLECLOUDS_TEST_OIT_MRT=0`
selects the two-pass baseline. `auto` (the harness default) uses capability selection;
`1` enables explicit candidate diagnostics. Full attachment parity readback additionally
requires `SIMPLECLOUDS_TEST_OIT_MRT_PARITY=1` and its exact scratch fixture. Normal
play does not enable readback diagnostics. Prior repeated1280x720 controlled scenes
measured approximately15%FPS improvement; this is not a guarantee for every world.

## License

The original mod is licensed under the PolyForm Perimeter License 1.0.1 — full terms in
[`LICENSE.md`](LICENSE.md) (<https://polyformproject.org/licenses/perimeter/1.0.1>). This port
is a changed version of it and is distributed under the same terms; `LICENSE` keeps the
original credits. From the original README:

> 
> Simple Clouds is licensed under [PolyForm Perimeter License 1.0.1](https://github.com/nonamecrackers2/simple-clouds/blob/1.20.1/LICENSE.md) by nonamecrackers2 unless otherwise stated. The following files contain code that are subject to different licenses:
> - [/src/main/resources/assets/simpleclouds/shaders/program/storm_fog.fsh](https://github.com/nonamecrackers2/simple-clouds/blob/658c05e5e97eb21b3106ee9940f19028e98722fa/src/main/resources/assets/simpleclouds/shaders/program/storm_fog.fsh#L64C1-L89C3)
> - [/src/main/resources/assets/simpleclouds/shaders/include/random.glsl](https://github.com/nonamecrackers2/simple-clouds/blob/1.20.1/src/main/resources/assets/simpleclouds/shaders/include/random.glsl)
> - [/src/main/resources/assets/simpleclouds/shaders/include/random_hash.glsl](https://github.com/nonamecrackers2/simple-clouds/blob/1.20.1/src/main/resources/assets/simpleclouds/shaders/include/random_hash.glsl)
> - [/src/main/resources/assets/simpleclouds/shaders/include/simplex_noise.glsl](https://github.com/nonamecrackers2/simple-clouds/blob/1.20.1/src/main/resources/assets/simpleclouds/shaders/include/simplex_noise.glsl)
> - [/src/main/resources/assets/simpleclouds/shaders/compute/cloud_regions.comp](https://github.com/nonamecrackers2/simple-clouds/blob/1.20.1/src/main/resources/assets/simpleclouds/shaders/compute/cloud_regions.comp)
> - [/src/main/resources/assets/simpleclouds/shaders/core/cloud_region_tex.fsh](https://github.com/nonamecrackers2/simple-clouds/blob/1.20.1/src/main/resources/assets/simpleclouds/shaders/core/cloud_region_tex.fsh)
> - [/src/main/java/dev/nonamecrackers2/simpleclouds/client/event/SimpleCloudsClientEvents.java](https://github.com/nonamecrackers2/simple-clouds/blob/1.20.1/src/main/java/dev/nonamecrackers2/simpleclouds/client/event/SimpleCloudsClientEvents.java)

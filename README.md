# Simple Clouds — Fabric 26.2 port

Unofficial port of [Simple Clouds](https://github.com/nonamecrackers2/simple-clouds) by
**nonamecrackers2** to Minecraft 26.2 on Fabric, made for a personal modpack. It is not
affiliated with or endorsed by the original author; all credit for the mod goes to them.

**Status: work in progress.** The port builds and runs, but visual parity with the 1.20.1
original and the storm effects are still being tested. No release jars are published here.

- `plans-claude-handoff.md` — current state, rules and next steps (read first)
- `PORT-PROGRESS-2026-09-14.md` — dated evidence log of every test run
- `PORTING.md`, `VISUAL-PARITY-*.md`, `STORM-PLAN.md` — how the port was done
- `dev-relaunch.sh`, `tools/` — isolated dev-client test runs and image metrics

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

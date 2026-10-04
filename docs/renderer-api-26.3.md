# Simple Clouds renderer API migration: 26.3 Fabric

The world renderer uses Minecraft's RenderPipeline/CommandEncoder texture API and original GPU-generated cloud geometry. Original blur, fog, shadow, depth and composition behavior lives in `CloudsDrawPipeline`; it does not use the original Forge raw-GL framebuffer post-chain API. This is an explicit API break, not a claim of binary compatibility with Forge addons.

## Custom pipelines

Implement `CloudsRenderPipeline` and respect its existing prepare/afterSky/beforeWeather/afterLevel callbacks. The built-in `DefaultPipeline` is the reference for the normal shader-free pack. It delegates world geometry/composition to `SimpleCloudsRenderer.renderCloudsAfterSky` and screen-space world fog to `doScreenSpaceWorldFog` in their corresponding stages. Use the exact signatures and ordering in that implementation; do not invoke both normal and late shader drawing in one frame. `ShaderSupportPipeline` remains optional historical compatibility, not required by Jan's final modpack.

The enclosing renderer owns per-pass preparation and resources. Do not resize or close its targets, use raw framebuffer IDs, or copy depth while a render pass is active. The draw backend owns depth transfers and dependency order. A whole-frame draw called separately for blur, storm fog, shadows and final composite would duplicate work and is not an adapter.

## Removed stage methods

These retained source-level declarations are deprecated and now throw `UnsupportedOperationException` with a migration pointer instead of silently returning without doing anything:

- `doBlurPostProcessing`
- `doFinalCompositePass`
- `doStormPostProcessing`
- `doCloudShadowProcessing`
- `copyDepthFromCloudsToMain`
- `copyDepthFromMainToClouds`
- `copyDepthFromCloudsToTransparency`

There are no repository callers of those legacy declarations. The built-in live world paths do not use them. External addons that use them must migrate their pipeline, not suppress the exception and assume a successful render. Supporting arbitrary external Forge post-chain addons is not established by this port.

## Image rendering

`CloudImageRenderer` is supported through its independent render target and async PNG exporter. Callers own the GPU generator they provide and must keep it alive through the render. Closing the renderer during an export defers target disposal until readback completes; it does not close the caller's generator or change the main window. See `CloudImageApiProbe` for an actual functional test of this contract.

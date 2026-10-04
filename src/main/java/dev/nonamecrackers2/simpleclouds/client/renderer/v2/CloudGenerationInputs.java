package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import java.util.List;

/**
 * Shader generation inputs, independent of the temporary CPU implementation.
 * Field order mirrors the original noise-layer, layer-group and region data.
 * This is a data contract, not a second cloud-generation algorithm.
 */
public final class CloudGenerationInputs {
    private CloudGenerationInputs() {}

    public record NoiseLayer(float height, float valueOffset, float scaleX, float scaleY, float scaleZ,
            float fadeDistance, float heightOffset, float valueScale) {}

    public record CloudLayerGroup(List<NoiseLayer> layers, float transparencyFade, boolean stormType,
            float storminess, float stormStart, float stormFadeDistance) {}

    public record RegionMask(float x, float z, float radius,
            float m00, float m01, float m10, float m11, int groupIndex) {}
}

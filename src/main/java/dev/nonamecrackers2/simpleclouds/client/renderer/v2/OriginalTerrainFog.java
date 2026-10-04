package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import net.minecraft.client.renderer.fog.FogData;

/** Original terrain fog policy adapted to modern sphere/cylinder components. */
public final class OriginalTerrainFog {
    private OriginalTerrainFog() {}

    public static void apply(FogData data, boolean off, float darkenFactor) {
        if (off) {
            // Keep finite, ordered edges beyond reachable world distances.
            // Modern native fog guards endpoints before interpolation.
            data.environmentalStart = Float.MAX_VALUE / 2;
            data.environmentalEnd = Float.MAX_VALUE;
            data.renderDistanceStart = Float.MAX_VALUE / 2;
            data.renderDistanceEnd = Float.MAX_VALUE;
        } else {
            if (!Float.isFinite(darkenFactor) || darkenFactor < 0 || darkenFactor > 1)
                throw new IllegalArgumentException("Invalid terrain fog darken factor");
            float storminess = (float)Math.sqrt(darkenFactor);
            data.environmentalStart *= storminess;
            data.renderDistanceStart *= storminess;
        }
        // Sky/cloud distance policy and color remain owned by their renderers.
    }
}

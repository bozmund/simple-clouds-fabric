package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

/** Original WorldEffects depthMask(useShaderTransparency || areShadersRunning). */
public final class OriginalPrecipitationDepth {
    private OriginalPrecipitationDepth() {}
    public static boolean writesDepth(boolean improvedTransparency, boolean shadersRunning) {
        return improvedTransparency || shadersRunning;
    }
}

package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import java.nio.ByteBuffer;
import org.joml.Matrix4f;

/** Original post-fog inputs, with a camera-relative modern zero-to-one projection. */
public final class OriginalWorldFogUniforms {
    public static final int BYTES = 160;
    private OriginalWorldFogUniforms() {}
    public static void write(ByteBuffer out, Matrix4f projection, Matrix4f viewRotation,
            float start, float end, float[] color, boolean storm) {
        Matrix4f inverseProjection = new Matrix4f(projection).invert();
        Matrix4f inverseRotation = new Matrix4f(viewRotation).setTranslation(0,0,0).invert();
        if(out.capacity()<BYTES || !inverseProjection.isFinite() || !inverseRotation.isFinite()
                || !Float.isFinite(start) || !Float.isFinite(end))
            throw new IllegalArgumentException("Invalid original world-fog inputs");
        inverseProjection.get(0,out); inverseRotation.get(64,out);
        out.putFloat(128,color[0]).putFloat(132,color[1]).putFloat(136,color[2]).putFloat(140,1);
        out.putFloat(144,start).putFloat(148,end).putInt(152,1).putInt(156,storm?1:0);
    }
}

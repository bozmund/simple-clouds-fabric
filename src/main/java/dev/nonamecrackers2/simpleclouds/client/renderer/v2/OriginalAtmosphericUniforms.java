package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import java.nio.ByteBuffer;
import org.joml.Matrix2f;
import org.joml.Matrix4f;

/** Original atmosphere inputs in a modern std140 block; no FOV approximation. */
public final class OriginalAtmosphericUniforms {
    public static final int BYTES=208;
    private OriginalAtmosphericUniforms() {}
    public static void write(ByteBuffer data, Matrix4f projection, Matrix4f view, Matrix2f transform,
            float shift, float density, float r, float g, float b, float alpha) {
        if(data.capacity()<BYTES) throw new IllegalArgumentException("Atmospheric block too small");
        Matrix4f inverseProjection=new Matrix4f(projection).invert();
        Matrix4f inverseView=new Matrix4f(view).invert();
        if(!inverseProjection.isFinite() || !inverseView.isFinite())
            throw new IllegalArgumentException("Atmospheric camera matrix is not invertible");
        inverseProjection.get(0,data); inverseView.get(64,data);
        data.putFloat(128,transform.m00()).putFloat(132,transform.m01());
        data.putFloat(144,transform.m10()).putFloat(148,transform.m11());
        data.putFloat(160,128f).putFloat(164,10000f).putFloat(168,10000f);
        data.putFloat(172,30000f).putFloat(176,10000f);
        data.putFloat(180,shift).putFloat(184,density).putFloat(188,5000f);
        data.putFloat(192,r).putFloat(196,g).putFloat(200,b).putFloat(204,alpha);
    }
}

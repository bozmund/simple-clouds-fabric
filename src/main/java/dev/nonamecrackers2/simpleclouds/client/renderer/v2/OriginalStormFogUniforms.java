package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import java.nio.ByteBuffer;
import org.joml.Matrix4f;

/** std140 original raymarch parameters; geometry remains entirely GPU-resident. */
public final class OriginalStormFogUniforms
{
	public static final int MAX_BOLTS = 16;
	public static final int BYTES = 560;
	private OriginalStormFogUniforms() {}
	public static void write(ByteBuffer data, Matrix4f projection, Matrix4f worldView,
			Matrix4f shadowProjection, Matrix4f shadowView, double x, double y, double z,
			float fogEnd, float[] color, float[] bolts, int count, int debug)
	{
		if (!(fogEnd > 0) || !Float.isFinite(fogEnd)) throw new IllegalArgumentException("Invalid fog end");
		if (count < 0 || count > MAX_BOLTS || bolts.length < count * 4) throw new IllegalArgumentException("Invalid lightning count");
		if (data.capacity() < BYTES || color.length < 3) throw new IllegalArgumentException("Incomplete fog uniform storage");
		new Matrix4f(projection).invert().get(0,data);
		new Matrix4f(worldView).invert().get(64,data);
		shadowProjection.get(128,data); shadowView.get(192,data);
		data.putFloat(256,(float)x).putFloat(260,(float)y).putFloat(264,(float)z).putFloat(268,count);
		data.putFloat(272,fogEnd/2).putFloat(276,fogEnd).putFloat(280,debug).putFloat(284,0);
		data.putFloat(288,color[0]).putFloat(292,color[1]).putFloat(296,color[2]).putFloat(300,1);
		for (int i=0;i<MAX_BOLTS*4;i++) data.putFloat(304+i*4,i<count*4?bolts[i]:0);
	}
}

package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import org.joml.Matrix4f;

/** Forge 1.20.1 storm shadow volume, accepting already-scaled world vertices. */
public final class OriginalStormShadowTransform
{
	public static final float FAR = 10000.0F;
	public static final float CLOUD_SCALE = 8.0F;
	public static final float CHUNK_WORLD_SIZE = 32.0F * CLOUD_SCALE;
	private OriginalStormShadowTransform() {}

	public static Matrix4f projection(float span)
	{
		if (!(span > 0) || !Float.isFinite(span))
			throw new IllegalArgumentException("Invalid storm shadow span: " + span);
		// 26.3 sets glClipControl(ZERO_TO_ONE) for the whole context: a GL-style [-1,1]
		// ortho clips the light-side half of the volume and stores depth on a scale the
		// fog's lookup does not use. zZeroToOne stores -viewZ/FAR, the original's value.
		return new Matrix4f().setOrtho(0.0F, span, span, 0.0F, 0.0F, FAR, true);
	}

	public static Matrix4f view(double camX, double camZ, float cloudHeight,
			float span, float angleDegrees, float windX, float windZ)
	{
		// The original snaps the volume to cloud chunks, not every camera movement.
		float snapX = (float)(Math.floor(camX / CHUNK_WORLD_SIZE) * CHUNK_WORLD_SIZE);
		float snapZ = (float)(Math.floor(camZ / CHUNK_WORLD_SIZE) * CHUNK_WORLD_SIZE);
		float yaw = (float)Math.atan2(windX, windZ);
		return new Matrix4f().translation(span / 2.0F, span / 2.0F, -FAR / 2.0F)
				.rotateX((float)Math.toRadians(angleDegrees)).rotateY(yaw)
				.translate(-snapX, -cloudHeight, -snapZ);
	}
}

package dev.nonamecrackers2.simpleclouds.client;

/**
 * Holds the last vanilla fog (sky) color, captured by {@code MixinFogRenderer} each
 * frame (VISUAL-PARITY-PLAN step 3). Kept in a plain class (not the mixin) because
 * mixin static members must be private and can't be read from elsewhere.
 */
public final class FogColorCapturer
{
	private static final float[] COLOR = { 0.63F, 0.81F, 0.92F };

	private FogColorCapturer()
	{
	}

	public static void set(float r, float g, float b)
	{
		COLOR[0] = r;
		COLOR[1] = g;
		COLOR[2] = b;
	}

	/** @return the last captured fog color (RGB, 0..1). */
	public static float[] get()
	{
		return COLOR;
	}
}

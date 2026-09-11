package net.minecraft.util;

/**
 * Fabric 26.2 compatibility: FastColor was removed in 26.2.
 * This is a minimal replacement to allow old rendering code to compile.
 */
public class FastColor
{
	public static final FastColor ARGB32 = new FastColor();

	public int red(int color)
	{
		return (color >> 16) & 0xFF;
	}

	public int green(int color)
	{
		return (color >> 8) & 0xFF;
	}

	public int blue(int color)
	{
		return color & 0xFF;
	}

	public int alpha(int color)
	{
		return (color >> 24) & 0xFF;
	}
}

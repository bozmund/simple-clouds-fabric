package net.minecraft.client.renderer;

/**
 * Fabric 26.2 compatibility: TextureTarget API changed in 26.2.
 * This is a minimal replacement to allow old rendering code to compile.
 */
public class TextureTarget extends RenderTarget
{
	public TextureTarget(int width, int height, boolean useDepth, boolean useColor)
	{
		super(width, height, useDepth);
	}
}

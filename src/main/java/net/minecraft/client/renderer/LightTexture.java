package net.minecraft.client.renderer;

/**
 * Fabric 26.2 compatibility: LightTexture was removed in 26.2.
 * This is a minimal replacement to allow old rendering code to compile.
 */
public class LightTexture
{
	private static final LightTexture INSTANCE = new LightTexture();

	public static LightTexture getInstance()
	{
		return INSTANCE;
	}

	public int getLightTextureCoordinates(net.minecraft.client.renderer.block.BlockAndTintGetter level, net.minecraft.core.BlockPos pos)
	{
		return 15728880; // Default light value
	}
}

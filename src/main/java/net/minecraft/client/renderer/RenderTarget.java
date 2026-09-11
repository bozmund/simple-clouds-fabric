package net.minecraft.client.renderer;

/**
 * Fabric 26.2 compatibility: RenderTarget API changed in 26.2.
 * This is a minimal replacement to allow old rendering code to compile.
 *
 * TODO(26.2-render): properly port rendering code to the new RenderTarget API.
 */
public class RenderTarget
{
	public int width;
	public int height;

	public RenderTarget(int width, int height, boolean useDepth)
	{
		this.width = width;
		this.height = height;
	}

	public void setClearColor(float r, float g, float b, float a)
	{
		// Placeholder
	}

	public void setFilterMode(int filterMode)
	{
		// Placeholder
	}

	public void setSampler(String name, java.util.function.Supplier<Integer> supplier)
	{
		// Placeholder
	}

	public int getDepthTextureId()
	{
		return 0;
	}

	public int getColorTextureId()
	{
		return 0;
	}

	public int getFramebufferId()
	{
		return 0;
	}

	public void bindReadBuffer()
	{
		// Placeholder
	}

	public void bindWriteBuffer()
	{
		// Placeholder
	}

	public void unbind()
	{
		// Placeholder
	}

	public void deleteTextures()
	{
		// Placeholder
	}

	public void resize(int width, int height)
	{
		this.width = width;
		this.height = height;
	}
}

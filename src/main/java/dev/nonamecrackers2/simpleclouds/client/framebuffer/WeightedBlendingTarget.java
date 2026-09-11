package dev.nonamecrackers2.simpleclouds.client.framebuffer;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;

/**
 * 26.2 vertical-slice stub. The weighted-blending (transparency revealage) target is
 * not ported yet; this keeps the type API-compatible (extends the new {@link RenderTarget})
 * so the renderer getters and dependent code compile. Port incrementally.
 */
public class WeightedBlendingTarget extends RenderTarget
{
	private boolean stencilEnabled;

	public WeightedBlendingTarget(int width, int height, boolean clearError, boolean highPrecisionDepth)
	{
		super("simpleclouds.weightedBlending", true, GpuFormat.RGBA8_UNORM);
	}

	public boolean isStencilEnabled()
	{
		return this.stencilEnabled;
	}

	public void enableStencil()
	{
		this.stencilEnabled = true;
	}

	public void setFilterMode(int mode)
	{
		// TODO(26.2): port filter mode.
	}

	public void bindWrite(boolean viewport)
	{
		// TODO(26.2): port bind-write.
	}

	public void clear(boolean clearErrors)
	{
		// TODO(26.2): port clear.
	}

	public int getRevealageTextureId()
	{
		// TODO(26.2): return the revealage texture id.
		return 0;
	}
}

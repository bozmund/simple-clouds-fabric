package dev.nonamecrackers2.simpleclouds.client.framebuffer;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;

/**
 * 26.2 vertical-slice stub. The cloud render target is not ported yet; this keeps the
 * type API-compatible (extends the new {@link RenderTarget}). Port incrementally.
 */
public class CloudRenderTarget extends RenderTarget
{
	public CloudRenderTarget(int width, int height, boolean clearError, boolean highPrecisionDepth)
	{
		super("simpleclouds.cloud", GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
	}
}

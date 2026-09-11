package dev.nonamecrackers2.simpleclouds.client.framebuffer;

import com.mojang.blaze3d.pipeline.RenderTarget;

/**
 * 26.2 vertical-slice stub. The alpha-preserving blit is not ported yet; this is a
 * no-op that keeps the API-compatible. Port incrementally.
 */
public class FrameBufferUtils
{
	public static void blitTargetPreservingAlpha(RenderTarget target, int width, int height)
	{
		// TODO(26.2): port the blit using the new RenderPass/pipeline model.
	}
}

package dev.nonamecrackers2.simpleclouds.client.renderer.pipeline;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;

/**
 * 26.2 vertical-slice: the default pipeline no longer drives the legacy framebuffer
 * cloud rendering. Opaque cloud rendering is done directly in
 * {@link SimpleCloudsRenderer#renderInWorld} through the new GPU pipeline. The pipeline
 * hooks are kept as no-ops so the API stays intact while the full renderer is ported.
 */
public class DefaultPipeline implements CloudsRenderPipeline
{
	protected DefaultPipeline() {}

	@Override
	public void prepare(Minecraft mc, SimpleCloudsRenderer renderer, PoseStack stack, Matrix4f projMat, float partialTick, double camX, double camY, double camZ, Frustum frustum)
	{
	}

	@Override
	public void afterSky(Minecraft mc, SimpleCloudsRenderer renderer, PoseStack stack, Matrix4f projMat, float partialTick, double camX, double camY, double camZ, Frustum frustum)
	{
		// TODO(26.2): re-integrate atmospheric clouds + storm fog + transparency via the new pipeline.
	}

	@Override
	public void beforeWeather(Minecraft mc, SimpleCloudsRenderer renderer, PoseStack stack, Matrix4f projMat, float partialTick, double camX, double camY, double camZ, Frustum frustum)
	{
	}

	@Override
	public void afterLevel(Minecraft mc, SimpleCloudsRenderer renderer, PoseStack stack, Matrix4f projMat, float partialTick, double camX, double camY, double camZ, Frustum frustum)
	{
	}
}

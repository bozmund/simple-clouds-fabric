package dev.nonamecrackers2.simpleclouds.client.renderer.pipeline;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;

/**
 * 26.2 vertical-slice: the shader-support pipeline no longer drives the legacy
 * framebuffer cloud rendering. Opaque cloud rendering is done directly in
 * {@link SimpleCloudsRenderer#renderInWorld}. Hooks are kept as no-ops.
 */
public class ShaderSupportPipeline implements CloudsRenderPipeline
{
	protected ShaderSupportPipeline() {}

	@Override
	public void prepare(Minecraft mc, SimpleCloudsRenderer renderer, PoseStack stack, Matrix4f projMat, float partialTick, double camX, double camY, double camZ, Frustum frustum) {}

	@Override
	public void afterSky(Minecraft mc, SimpleCloudsRenderer renderer, PoseStack stack, Matrix4f projMat, float partialTick, double camX, double camY, double camZ, Frustum frustum) {}

	@Override
	public void beforeWeather(Minecraft mc, SimpleCloudsRenderer renderer, PoseStack stack, Matrix4f projMat, float partialTick, double camX, double camY, double camZ, Frustum frustum) {}

	// To make Iris shaders work at a bare minimum, we render the clouds after the Iris render pipeline
	@Override
	public void afterLevel(Minecraft mc, SimpleCloudsRenderer renderer, PoseStack stack, Matrix4f projMat, float partialTick, double camX, double camY, double camZ, Frustum frustum)
	{
		// TODO(26.2): re-integrate the shader-support cloud pass via the new pipeline.
	}
}

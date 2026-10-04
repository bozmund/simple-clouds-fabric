package dev.nonamecrackers2.simpleclouds.client.renderer.pipeline;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;

/**
 * Original stage ownership adapted to the modern GPU pipeline: afterSky owns cloud
 * geometry/composition and beforeWeather owns screen-space fog. prepare/afterLevel
 * intentionally remain empty, as in the original default pipeline.
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
		renderer.renderCloudsAfterSky(stack, projMat, partialTick, camX, camY, camZ);
	}

	@Override
	public void beforeWeather(Minecraft mc, SimpleCloudsRenderer renderer, PoseStack stack, Matrix4f projMat, float partialTick, double camX, double camY, double camZ, Frustum frustum)
	{
		if (dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig.CLIENT.fogMode.get()
				== dev.nonamecrackers2.simpleclouds.client.world.FogRenderMode.SCREEN_SPACE
				&& mc.gameRenderer.mainCamera().getFluidInCamera() == net.minecraft.world.level.material.FogType.NONE)
			renderer.doScreenSpaceWorldFog(stack, projMat, partialTick);
	}

	@Override
	public void afterLevel(Minecraft mc, SimpleCloudsRenderer renderer, PoseStack stack, Matrix4f projMat, float partialTick, double camX, double camY, double camZ, Frustum frustum)
	{
	}
}

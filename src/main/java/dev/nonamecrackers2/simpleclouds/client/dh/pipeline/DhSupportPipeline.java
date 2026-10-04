package dev.nonamecrackers2.simpleclouds.client.dh.pipeline;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;
import dev.nonamecrackers2.simpleclouds.client.renderer.pipeline.CloudsRenderPipeline;
import dev.nonamecrackers2.simpleclouds.client.world.FogRenderMode;
import dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.level.material.FogType;

/**
 * Distant Horizons pipeline, as in the 1.20.1 original: DH pastes its LODs over every
 * pixel it covers, so only the atmospheric layer is drawn after the sky; clouds, storm
 * fog and weather are drawn after the level, against DH's depth.
 */
public class DhSupportPipeline implements CloudsRenderPipeline
{
	public static final DhSupportPipeline INSTANCE = new DhSupportPipeline();

	private DhSupportPipeline() {}

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
		if (SimpleCloudsConfig.CLIENT.fogMode.get() == FogRenderMode.SCREEN_SPACE
				&& mc.gameRenderer.mainCamera().getFluidInCamera() == FogType.NONE)
			renderer.doScreenSpaceWorldFog(stack, projMat, partialTick);
	}

	@Override
	public void afterLevel(Minecraft mc, SimpleCloudsRenderer renderer, PoseStack stack, Matrix4f projMat, float partialTick, double camX, double camY, double camZ, Frustum frustum)
	{
		renderer.renderCloudsAfterShaderLevel(stack, projMat, partialTick, camX, camY, camZ);
	}
}

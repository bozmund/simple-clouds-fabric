package dev.nonamecrackers2.simpleclouds.mixin;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;

import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;

/**
 * 26.2 re-hook. The old LevelRenderer methods (renderLevel, renderClouds, renderSky,
 * renderSnowAndRain, tick, tickRain) are gone — 26.2 uses a pass-based frame graph.
 * For the vertical slice we hook the single entry point {@code render(...)} and draw
 * the opaque clouds there. The proper long-term integration is a custom pass added to
 * the {@code FrameGraphBuilder} (see PORTING.md).
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer
{
	private static final Logger LOGGER = LogManager.getLogger("simpleclouds/MixinLevelRenderer");
	private static boolean loggedError;

	@Inject(method = "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V", at = @At("HEAD"), require = 1)
	private void simpleclouds$beginFrame(CallbackInfo ci)
	{
		SimpleCloudsRenderer.getOptionalInstance().ifPresent(SimpleCloudsRenderer::beginWorldRenderFrame);
	}

	/**
	 * With Distant Horizons the clouds are drawn between the opaque stage and the translucent
	 * stage of the main pass: DH has already applied its LODs (prepareTranslucents HEAD) and the
	 * solid terrain is in the depth buffer, while translucent terrain, particles and weather are
	 * still to come and blend over the clouds, as in the original. Drawn at the TAIL instead,
	 * the clouds were depth-tested against rain particles and every drop left a hole showing the
	 * world behind the cloud (Jan, 2026-10-04). The vanilla pass spans both stages, so it is
	 * closed here (close() is idempotent) and the translucent stage gets a fresh pass on the
	 * same targets.
	 */
	@WrapOperation(
			method = "lambda$addMainPass$0",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;executeClassicTransparency(Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"),
			require = 0)
	private void simpleclouds$cloudsBeforeTranslucent(LevelRenderer self, ChunkSectionsToRender sections,
			FeatureRenderDispatcher.PreparedFrame frame, RenderPass pass, Operation<Void> original)
	{
		SimpleCloudsRenderer renderer = SimpleCloudsRenderer.getOptionalInstance().orElse(null);
		if (renderer == null || !renderer.wantsCloudsBeforeTranslucent())
		{
			original.call(self, sections, frame, pass);
			return;
		}
		pass.close();
		try
		{
			renderer.renderCloudsBeforeTranslucent();
		}
		catch (Throwable t)
		{
			if (!loggedError)
			{
				LOGGER.error("Simple Clouds: cloud render pass failed (further failures suppressed)", t);
				loggedError = true;
			}
		}
		var main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		try (RenderPass translucent = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
				() -> "simpleclouds.translucentAfterClouds", main.getColorTextureView(), java.util.Optional.empty(),
				main.getDepthTextureView(), java.util.OptionalDouble.empty()))
		{
			RenderSystem.bindDefaultUniforms(translucent);
			original.call(self, sections, frame, translucent);
		}
	}

	/**
	 * Distant Horizons' vanilla fade runs at the HEAD of executeOutline, after the clouds, and
	 * blends LOD colour over everything at the vanilla render-distance edge. Cloud pixels get
	 * their colour back right after it (CloudsDrawPipeline.endDhFadeGuard).
	 */
	@WrapOperation(
			method = "lambda$addMainPass$0",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;executeOutline(Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;)V"),
			require = 0)
	private void simpleclouds$guardCloudsFromDhFade(LevelRenderer self, FeatureRenderDispatcher.PreparedFrame frame,
			Operation<Void> original)
	{
		SimpleCloudsRenderer renderer = SimpleCloudsRenderer.getOptionalInstance().orElse(null);
		if (renderer != null)
		{
			try { renderer.beginDhFadeGuard(); }
			catch (Throwable t) { renderer = null; }
		}
		original.call(self, frame);
		if (renderer != null)
		{
			try
			{
				renderer.endDhFadeGuard();
			}
			catch (Throwable t)
			{
				if (!loggedError)
				{
					LOGGER.error("Simple Clouds: DH fade guard failed (further failures suppressed)", t);
					loggedError = true;
				}
			}
		}
	}

	/**
	 * 26.2 3D previewer: while the previewer screen is open, draw the preview into
	 * its dedicated offscreen target HERE (the vanilla Projection uniform still holds
	 * the world's perspective projection; in the GUI phase it is the 2D GUI
	 * projection and would clip the cubes). The screen blits the target fullscreen
	 * in its GUI phase, which draws on top of the world. The normal cloud pass is
	 * skipped for the frame.
	 */
	@Inject(
			method = "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V",
			at = @At("TAIL"))
	public void simpleclouds$renderClouds_render(CallbackInfo ci)
	{
		try
		{
			Minecraft mc = Minecraft.getInstance();
			ClientLevel level = mc.level;
			if (SimpleCloudsRenderer.getOptionalInstance().isEmpty() || !SimpleCloudsRenderer.canRenderInDimension(level))
				return;
			// Iris finalizes at this same method's TAIL. Draw its late cloud stage
			// at the enclosing GameRenderer boundary, after every Iris TAIL callback.
			if (nonamecrackers2.crackerslib.common.compat.CompatHelper.areShadersRunning()) return;

			var camera = mc.gameRenderer.mainCamera();
			var pos = camera.position();
			float partialTick = mc.getDeltaTracker() != null ? mc.getDeltaTracker().getGameTimeDeltaPartialTick(false) : 0.0F;

			SimpleCloudsRenderer.getInstance().renderAfterLevel(partialTick, pos.x, pos.y, pos.z);
			// Dev-only deterministic screenshot for dev-relaunch.sh (no-op without run/devshot.request).
			dev.nonamecrackers2.simpleclouds.client.DevShot.onWorldFrame();
		}
		catch (Throwable t)
		{
			// Never take down the game over a cloud-pass failure; log once.
			if (!loggedError)
			{
				LOGGER.error("Simple Clouds: cloud render pass failed (further failures suppressed)", t);
				loggedError = true;
			}
		}
	}

}

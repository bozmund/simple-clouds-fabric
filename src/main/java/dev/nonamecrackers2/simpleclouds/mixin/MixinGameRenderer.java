package dev.nonamecrackers2.simpleclouds.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.GameRenderer;

/**
 * Captures explicitly opted-in editor test frames after GUI rendering. Normal play
 * is a no-op. Do not inject bobView into the view matrix: 26.3 applies bobbing to
 * projection, and the renderer deliberately uses the camera's actual yaw/pitch.
 */
@Mixin(GameRenderer.class)
public class MixinGameRenderer
{
	@org.spongepowered.asm.mixin.Unique private static boolean simpleclouds$lateShaderError;
	// All LevelRenderer/Iris finalizers have returned here, while the world
	// projection and terrain depth still exist. render3dHud replaces the former
	// and clears the latter: renderLevel TAIL is too late for world geometry.
	@Inject(method = "renderLevel()V", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V",
			shift = At.Shift.BEFORE), require = 1)
	private void simpleclouds$afterIrisWorld(CallbackInfo ci) {
		if (!nonamecrackers2.crackerslib.common.compat.CompatHelper.areShadersRunning()) return;
		var mc = net.minecraft.client.Minecraft.getInstance();
		if (!dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer.canRenderInDimension(mc.level)) return;
		try {
			var renderer = dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer.getOptionalInstance();
			if (renderer.isEmpty()) return;
			var pos = mc.gameRenderer.mainCamera().position();
			renderer.get().renderAfterLevel(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false),pos.x,pos.y,pos.z);
			dev.nonamecrackers2.simpleclouds.client.DevShot.onWorldFrame();
		} catch (Throwable failure) {
			if (!simpleclouds$lateShaderError) {
				simpleclouds$lateShaderError=true;
				org.apache.logging.log4j.LogManager.getLogger("simpleclouds/MixinGameRenderer")
					.error("Simple Clouds: cloud render pass failed (after Iris)",failure);
			}
		}
	}
	@Inject(method = "render", at = @At("TAIL"))
	private void simpleclouds$capturePreviewGui(CallbackInfo ci) {
		dev.nonamecrackers2.simpleclouds.client.PreviewRuntimeProbe.afterGuiFrame();
	}
}


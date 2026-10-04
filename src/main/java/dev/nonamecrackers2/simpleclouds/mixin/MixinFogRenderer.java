package dev.nonamecrackers2.simpleclouds.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.nonamecrackers2.simpleclouds.client.FogColorCapturer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;

/**
 * Captures the vanilla fog (sky) color each frame so the mod's cloud fog can match
 * it (VISUAL-PARITY-PLAN step 3: "the fog color is the vanilla fog (sky) color").
 * The 26.2 FogRenderer computes the color into a FogData and has no public getter,
 * so we grab the return value of setupFog at the tail.
 */
@Mixin(FogRenderer.class)
public class MixinFogRenderer
{
	@Unique
	private static boolean simpleclouds$loggedStormFog;
	@Inject(method = "setupFog(Lnet/minecraft/client/Camera;ILnet/minecraft/client/DeltaTracker;FLnet/minecraft/client/multiplayer/ClientLevel;)Lnet/minecraft/client/renderer/fog/FogData;", at = @At("TAIL"), require = 1)
	private void simpleclouds$captureFogColor(Camera camera, int tick, DeltaTracker deltaTracker, float partialTick, ClientLevel level, CallbackInfoReturnable<FogData> ci)
	{
		FogData data = ci.getReturnValue();
		if (data != null && camera.getFluidInCamera() == net.minecraft.world.level.material.FogType.NONE)
		{
			var mode = dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig.CLIENT.fogMode.get();
			dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer.getOptionalInstance().ifPresent(renderer -> {
				float factor = renderer.getWorldEffectsManager().getDarkenFactor(deltaTracker.getGameTimeDeltaPartialTick(false), 2.0F);
				float before = data.renderDistanceStart;
				dev.nonamecrackers2.simpleclouds.client.renderer.v2.OriginalTerrainFog.apply(data,
						mode == dev.nonamecrackers2.simpleclouds.client.world.FogRenderMode.OFF, factor);
				if (!simpleclouds$loggedStormFog && factor < 0.95F && "1".equals(System.getenv("SIMPLECLOUDS_DEV"))) {
					simpleclouds$loggedStormFog = true;
					org.apache.logging.log4j.LogManager.getLogger("simpleclouds/TerrainFog")
							.info("[TERRAIN-FOG] mode={} factor={} start={} -> {} end={} skyEnd={} cloudEnd={}",
									mode, factor, before, data.renderDistanceStart, data.renderDistanceEnd, data.skyEnd, data.cloudEnd);
				}
			});
		}
		if (data != null && data.color != null)
		{
			FogColorCapturer.captureRanges(data.renderDistanceStart, data.renderDistanceEnd);
			if (camera.getFluidInCamera() == net.minecraft.world.level.material.FogType.NONE
					&& dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig.CLIENT.fogMode.get()
						!= dev.nonamecrackers2.simpleclouds.client.world.FogRenderMode.OFF)
				dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer.getOptionalInstance().ifPresent(renderer -> {
					var color = renderer.getWorldEffectsManager().calculateFogColor(data.color.x, data.color.y, data.color.z,
						deltaTracker.getGameTimeDeltaPartialTick(false));
					data.color.set(color.getRed()/255.0F, color.getGreen()/255.0F, color.getBlue()/255.0F, data.color.w);
				});
			FogColorCapturer.set(data.color.x, data.color.y, data.color.z);
		}
	}
}

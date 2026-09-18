package dev.nonamecrackers2.simpleclouds.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.nonamecrackers2.simpleclouds.client.compat.SimpleCloudsCompatHelper;
import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.world.phys.Vec3;

/**
 * Step 5 (original parity): when the mod owns the weather and renders its own rain
 * (renderCustomRain), cancel the 26.2 vanilla rain/snow pass so it does not double up
 * with the mod's custom drops. This is the 26.2 equivalent of the 1.20.1 original's
 * {@code MixinLevelRenderer#simpleclouds$overrideRainRendering_renderSnowAndRain}
 * (which cancelled {@code LevelRenderer.renderSnowAndRain}). Only the rain is cancelled
 * here; the world border (a separate call in the same weather pass) is untouched.
 */
@Mixin(WeatherEffectRenderer.class)
public class MixinWeatherEffectRenderer
{
	@Inject(method = "render(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/client/renderer/state/level/WeatherRenderState;)V", at = @At("HEAD"), cancellable = true)
	private void simpleclouds$cancelVanillaRain_render(Vec3 cameraPos, WeatherRenderState renderState, CallbackInfo ci)
	{
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null
				&& SimpleCloudsRenderer.canRenderInDimension(mc.level)
				&& SimpleCloudsCompatHelper.renderCustomRain())
			ci.cancel();
	}
}

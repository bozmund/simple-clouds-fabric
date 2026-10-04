package dev.nonamecrackers2.simpleclouds.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.nonamecrackers2.simpleclouds.client.compat.SimpleCloudsCompatHelper;
import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.state.level.WeatherRenderState;

/**
 * Suppress native precipitation for custom weather, or defer it past terrain post-fog.
 * Both classic and OIT slots capture current-frame columns. The explicit replay bypasses
 * only this interception, preventing us from cancelling our own later render call.
 */
@Mixin(WeatherEffectRenderer.class)
public class MixinWeatherEffectRenderer
{
	@org.spongepowered.asm.mixin.Unique private static boolean simpleclouds$nativeReturnLogged;
	@org.spongepowered.asm.mixin.Unique
	private static void simpleclouds$observeNativeReturn(WeatherRenderState state) {
		if (!simpleclouds$nativeReturnLogged && "1".equals(System.getenv("SIMPLECLOUDS_DEV"))
				&& "1".equals(System.getenv("SIMPLECLOUDS_TEST_SHADER_STAGE"))
				&& !simpleclouds$modOwnsWeather() && !state.rainColumns.isEmpty()) {
			simpleclouds$nativeReturnLogged=true;
			org.apache.logging.log4j.LogManager.getLogger("simpleclouds/NativeWeatherProbe")
					.info("[NATIVE-WEATHER-RETURN] rain={} snow={} deferred=false",state.rainColumns.size(),state.snowColumns.size());
		}
	}
	@Inject(method = "render(Lnet/minecraft/client/renderer/state/level/WeatherRenderState;Lcom/mojang/renderpearl/api/commands/RenderPass;)V", at=@At("RETURN"), require=1)
	private void simpleclouds$nativeReturn(WeatherRenderState state,RenderPass pass,CallbackInfo ci) {
		simpleclouds$observeNativeReturn(state);
	}
	@Inject(method = "renderOit(Lnet/minecraft/client/renderer/oit/OitStage;Lnet/minecraft/client/renderer/state/level/WeatherRenderState;Lcom/mojang/renderpearl/api/commands/RenderPass;)V", at=@At("RETURN"), require=1)
	private void simpleclouds$nativeOitReturn(net.minecraft.client.renderer.oit.OitStage stage,WeatherRenderState state,RenderPass pass,CallbackInfo ci) {
		simpleclouds$observeNativeReturn(state);
	}
	private static boolean simpleclouds$modOwnsWeather()
	{
		if (SimpleCloudsRenderer.isRenderingDeferredWeather()) return false;
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null
				&& SimpleCloudsRenderer.canRenderInDimension(mc.level)
				&& (SimpleCloudsCompatHelper.renderCustomRain() || SimpleCloudsRenderer.redrawsVanillaWeather());
	}

	@Inject(method = "render(Lnet/minecraft/client/renderer/state/level/WeatherRenderState;Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
			at = @At("HEAD"), cancellable = true, require = 1)
	private void simpleclouds$cancelVanillaRain_render(WeatherRenderState state, RenderPass pass, CallbackInfo ci)
	{
		if (!simpleclouds$modOwnsWeather())
			return;
		if (SimpleCloudsRenderer.redrawsVanillaWeather())
			SimpleCloudsRenderer.captureWeatherState(state);
		ci.cancel();
	}

	@Inject(method = "renderOit(Lnet/minecraft/client/renderer/oit/OitStage;Lnet/minecraft/client/renderer/state/level/WeatherRenderState;Lcom/mojang/renderpearl/api/commands/RenderPass;)V", at = @At("HEAD"), cancellable = true, require = 1)
	private void simpleclouds$cancelVanillaRain_renderOit(net.minecraft.client.renderer.oit.OitStage stage, WeatherRenderState state, RenderPass pass, CallbackInfo ci)
	{
		if (simpleclouds$modOwnsWeather())
		{
			if (SimpleCloudsRenderer.redrawsVanillaWeather()) SimpleCloudsRenderer.captureWeatherState(state);
			ci.cancel();
		}
	}
}


package dev.nonamecrackers2.simpleclouds.mixin;

import static dev.nonamecrackers2.simpleclouds.common.event.TickChunks.rainAndSnowVanillaCompatibility;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.nonamecrackers2.simpleclouds.common.world.CloudData;
import dev.nonamecrackers2.simpleclouds.common.world.CloudManager;
import dev.nonamecrackers2.simpleclouds.common.world.CloudManagerHolder;
import dev.nonamecrackers2.simpleclouds.common.world.ServerCloudManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.SavedDataStorage;


@Mixin(ServerLevel.class)
public abstract class MixinServerLevel implements CloudManagerHolder<ServerLevel>
{
	@Unique
	private ServerCloudManager cloudManager;
	@Shadow @Final
	private MinecraftServer server;
	
	@Inject(method = "<init>", at = @At("TAIL"))
	public void simpleclouds$createCloudManager_init(CallbackInfo ci)
	{
		this.cloudManager = new ServerCloudManager((ServerLevel)(Object)this);
		//Do this so we hide the world seed
		// 26.2: the world seed now lives in the worldgen settings (WorldOptions.seed()).
		this.cloudManager.init(RandomSource.create(this.server.getWorldGenSettings().options().seed()).nextLong());
		// 26.2: persistence moved to the SavedDataType registry (codec-based).
		this.getDataStorage().computeIfAbsent(CloudData.TYPE).attach(this.cloudManager);
	}
	
	@Inject(method = "advanceWeatherCycle", at = @At("HEAD"), cancellable = true)
	public void simpleclouds$disableWeatherCycle_advanceWeatherCycle(CallbackInfo ci)
	{
		ServerLevel level = (ServerLevel)(Object)this;
		if (level.canHaveWeather() && !this.cloudManager.shouldUseVanillaWeather())
		{
			// 26.3 stores WeatherData on MinecraftServer, shared by dimensions.
			// Only its primary level may clear that global cycle. Other custom
			// dimensions suppress their own rain without clearing Overworld rain.
			if (level == this.server.overworld())
				this.resetWeatherCycle();
			else {
				level.setRainLevel(0.0F);
				level.setThunderLevel(0.0F);
			}
			ci.cancel();
		}
	}
	@Inject(method = "tickChunk", at = @At(value = "RETURN"))
	public void simpleclouds$localizedWeatherHandlePrecipitation(LevelChunk chunk, int tickSpeed, CallbackInfo ci)
	{
		CloudManager<?> manager = CloudManager.get((Level)(Object)this);
		if (!manager.shouldUseVanillaWeather())
		{
			rainAndSnowVanillaCompatibility((ServerLevel)(Object)this, chunk);
		}
	}
	
	@Shadow
	protected abstract void resetWeatherCycle();

	// Only the successful collective sleep branch calls wakeUpAllPlayers from tick.
	// Do not clear storms when one player leaves a bed or disconnects.
	@Inject(method = "tick", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/server/level/ServerLevel;wakeUpAllPlayers()V"), require = 1)
	private void simpleclouds$clearStormsAfterSleep(CallbackInfo ci)
	{
		dev.nonamecrackers2.simpleclouds.common.event.SimpleCloudsEvents
				.removeStormsAfterSleeping((ServerLevel)(Object)this);
	}
	
	@Override
	public ServerCloudManager getCloudManager()
	{
		return this.cloudManager;
	}
	
	@Shadow
	public abstract SavedDataStorage getDataStorage();
}

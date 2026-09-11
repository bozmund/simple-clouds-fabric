package dev.nonamecrackers2.simpleclouds.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.nonamecrackers2.simpleclouds.client.world.ClientCloudManager;
import dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig;
import dev.nonamecrackers2.simpleclouds.common.world.CloudManagerHolder;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.WritableLevelData;

/**
 * 26.2: makes ClientLevel carry a ClientCloudManager (the packet sync path casts to
 * CloudManagerHolder). The old sky-color hooks are deferred — they targeted methods that
 * changed in 26.2 and depend on the (still stubbed) world-effects renderer.
 */
@Mixin(ClientLevel.class)
public abstract class MixinClientLevel extends Level implements CloudManagerHolder<ClientLevel>
{
	protected MixinClientLevel(WritableLevelData data, ResourceKey<Level> dimension, RegistryAccess registry, Holder<DimensionType> dimensionType, boolean isClientSide, boolean isDebug, long seed, int maxChainedNeighbourUpdates)
	{
		super(data, dimension, registry, dimensionType, isClientSide, isDebug, seed, maxChainedNeighbourUpdates);
		throw new UnsupportedOperationException();
	}

	@Unique
	private ClientCloudManager cloudManager;

	@Inject(method = "<init>", at = @At("TAIL"))
	public void simpleclouds$createCloudManager_init(CallbackInfo ci)
	{
		this.cloudManager = new ClientCloudManager((ClientLevel)(Object)this);
		this.cloudManager.init(SimpleCloudsConfig.CLIENT.useSpecificSeed.get() ? SimpleCloudsConfig.CLIENT.cloudSeed.get() : RandomSource.create().nextLong());
	}

	@Override
	public ClientCloudManager getCloudManager()
	{
		return this.cloudManager;
	}
}

package dev.nonamecrackers2.simpleclouds.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.multiplayer.ClientLevel;

/**
 * 26.2: ClientLevel.skyFlashTime has no public getter (vanilla's setSkyFlashTime
 * is public, the read is package-internal). Storm plan step 1: the port drives
 * the storm-fog flash lift from the vanilla sky-flash duration, so it needs the
 * read.
 */
@Mixin(ClientLevel.class)
public interface MixinClientLevelAccessor
{
	@Accessor("skyFlashTime")
	int simpleclouds$getSkyFlashTime();
}

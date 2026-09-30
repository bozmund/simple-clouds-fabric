package dev.nonamecrackers2.simpleclouds.mixin;

import dev.nonamecrackers2.simpleclouds.common.event.SimpleCloudsEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerPlayer.class)
public abstract class MixinServerPlayer
{
	@Redirect(method = "startSleepInBed", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/attribute/BedRule;canSleep(Lnet/minecraft/world/level/Level;)Z"), require = 1)
	private boolean simpleclouds$allowLocalThunderSleep(BedRule rule, Level level)
	{
		// Extend only the night/weather condition. Keep forbidden beds, distance,
		// obstruction and nearby-monster checks intact.
		return rule.canSleep(level) || (rule.canSleep() == BedRule.Rule.WHEN_DARK
				&& SimpleCloudsEvents.allowSleepingDuringThunderClouds((ServerPlayer)(Object)this));
	}
}

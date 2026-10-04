package dev.nonamecrackers2.simpleclouds.mixin;

import dev.nonamecrackers2.simpleclouds.common.event.SimpleCloudsEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Player.class)
public abstract class MixinPlayer
{
	@Redirect(method = "tick", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/attribute/BedRule;canSleep(Lnet/minecraft/world/level/Level;)Z"), require = 1)
	private boolean simpleclouds$keepLocalThunderSleeper(BedRule rule, Level level)
	{
		// 26.3 repeats the bed weather check every server tick, not just on entry.
		// Match the entry hook without allowing forbidden beds or client authority.
		return rule.canSleep(level) || (rule.canSleep() == BedRule.Rule.WHEN_DARK
				&& (Object)this instanceof ServerPlayer player
				&& SimpleCloudsEvents.allowSleepingDuringThunderClouds(player));
	}
}

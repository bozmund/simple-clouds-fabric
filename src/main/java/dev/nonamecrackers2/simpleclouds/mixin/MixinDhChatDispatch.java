package dev.nonamecrackers2.simpleclouds.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional DH 3.x adapter; no DH classes are linked when that mod is absent. */
@Pseudo
@Mixin(targets="com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftClientWrapper",remap=false)
public abstract class MixinDhChatDispatch {
    @Inject(method="sendChatMessage(Ljava/lang/String;)V",at=@At("HEAD"),cancellable=true,require=1,remap=false)
    private void simpleclouds$deferChatUntilClientTick(String message,CallbackInfo ci) {
        dev.nonamecrackers2.simpleclouds.client.dh.DhChatDispatch.enqueue(message);
        ci.cancel();
    }
}

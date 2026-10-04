package dev.nonamecrackers2.simpleclouds.mixin;

import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional exact adapter for the installed Voice Chat ChatUtils endpoint. */
@Pseudo
@Mixin(targets="de.maxhenkel.voicechat.voice.client.ChatUtils",remap=false)
public abstract class MixinVoiceChatDispatch {
    @Inject(method="sendPlayerMessage(Lnet/minecraft/network/chat/Component;)V",at=@At("HEAD"),cancellable=true,require=1,remap=false)
    private static void simpleclouds$deferVoiceChat(Component message,CallbackInfo ci) {
        dev.nonamecrackers2.simpleclouds.client.compat.VoiceChatDispatch.enqueue(message);
        ci.cancel();
    }
}

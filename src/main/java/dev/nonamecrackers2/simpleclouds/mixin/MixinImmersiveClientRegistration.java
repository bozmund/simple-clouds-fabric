package dev.nonamecrackers2.simpleclouds.mixin;

import dev.nonamecrackers2.simpleclouds.client.compat.NativeWeatherRegistration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets="com.thedeathlycow.immersive.storms.ImmersiveStormsClient",remap=false)
public abstract class MixinImmersiveClientRegistration {
    @Inject(method="onInitializeClient",at=@At("TAIL"),require=1,remap=false)
    private void simpleclouds$restoreNativeOwner(CallbackInfo ci) {
        NativeWeatherRegistration.afterNativeInitialization();
    }
}

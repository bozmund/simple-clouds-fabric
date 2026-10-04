package dev.nonamecrackers2.simpleclouds.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig;
import dev.nonamecrackers2.simpleclouds.common.world.LocalWeatherEffects;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets="com.thedeathlycow.immersive.storms.world.StormFogModifier",remap=false)
public abstract class MixinImmersiveFogControl {
    @Inject(method="shouldApply(Lnet/minecraft/world/level/Level;)Z",at=@At("HEAD"),cancellable=true,require=1,remap=false)
    private static void simpleclouds$biomeFogEnabled(CallbackInfoReturnable<Boolean> ci) {
        if(!SimpleCloudsConfig.CLIENT.biomeStormEffects.get()) {
            dev.nonamecrackers2.simpleclouds.client.compat.ImmersiveDhFogLifecycle.release();
            ci.setReturnValue(false);
        }
    }

    @Inject(method="shouldApply(Lnet/minecraft/world/level/Level;)Z",at=@At("RETURN"),require=1,remap=false)
    private static void simpleclouds$releaseDryFog(CallbackInfoReturnable<Boolean> ci) {
        if(!ci.getReturnValue())dev.nonamecrackers2.simpleclouds.client.compat.ImmersiveDhFogLifecycle.release();
    }
    @Inject(method="setFogDistanceForDistantHorizons(F)V",at=@At("HEAD"),cancellable=true,require=1,remap=false)
    private static void simpleclouds$captureDhWrite(float reduction,org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if(dev.nonamecrackers2.simpleclouds.client.compat.ImmersiveDhFogLifecycle.beforeWrite(reduction))ci.cancel();
    }
    @Inject(method="setFogDistanceForDistantHorizons(F)V",at=@At("TAIL"),require=1,remap=false)
    private static void simpleclouds$recordDhWrite(float reduction,org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        dev.nonamecrackers2.simpleclouds.client.compat.ImmersiveDhFogLifecycle.afterWrite();
    }

    @Redirect(method="sampleWeatherFogColor",at=@At(value="INVOKE",target="Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"),require=1,remap=false)
    private static float simpleclouds$colorRain(ClientLevel receiver,float partial,ClientLevel world,Vec3 pos,float tick,org.joml.Vector3fc color) {
        var sample=LocalWeatherEffects.at(world,BlockPos.containing(pos));
        return sample.localAuthority()?sample.rain():receiver.getRainLevel(partial);
    }
    @Redirect(method="sampleWeatherFogColor",at=@At(value="INVOKE",target="Lnet/minecraft/client/multiplayer/ClientLevel;getThunderLevel(F)F"),require=1,remap=false)
    private static float simpleclouds$colorThunder(ClientLevel receiver,float partial,ClientLevel world,Vec3 pos,float tick,org.joml.Vector3fc color) {
        var sample=LocalWeatherEffects.at(world,BlockPos.containing(pos));
        return sample.localAuthority()?sample.thunder():receiver.getThunderLevel(partial);
    }
    @Redirect(method="applyStartEndModifier",at=@At(value="INVOKE",target="Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"),require=1,remap=false)
    private static float simpleclouds$distanceRain(ClientLevel receiver,float partial,net.minecraft.client.renderer.fog.FogData fog,Vec3 pos,ClientLevel world,net.minecraft.client.DeltaTracker ticks) {
        var sample=LocalWeatherEffects.at(world,BlockPos.containing(pos));
        return sample.localAuthority()?sample.rain():receiver.getRainLevel(partial);
    }
    @Redirect(method="applyStartEndModifier",at=@At(value="INVOKE",target="Lnet/minecraft/client/multiplayer/ClientLevel;getThunderLevel(F)F"),require=1,remap=false)
    private static float simpleclouds$distanceThunder(ClientLevel receiver,float partial,net.minecraft.client.renderer.fog.FogData fog,Vec3 pos,ClientLevel world,net.minecraft.client.DeltaTracker ticks) {
        var sample=LocalWeatherEffects.at(world,BlockPos.containing(pos));
        return sample.localAuthority()?sample.thunder():receiver.getThunderLevel(partial);
    }
    @Redirect(method="shouldApply",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;getRainLevel(F)F"),require=1,remap=false)
    private static float simpleclouds$cameraRain(net.minecraft.world.level.Level world,float partial) {
        var mc=net.minecraft.client.Minecraft.getInstance();
        if(mc.level!=world) return world.getRainLevel(partial);
        var sample=LocalWeatherEffects.at(world,BlockPos.containing(mc.gameRenderer.mainCamera().position()));
        return sample.localAuthority()?sample.rain():world.getRainLevel(partial);
    }
}

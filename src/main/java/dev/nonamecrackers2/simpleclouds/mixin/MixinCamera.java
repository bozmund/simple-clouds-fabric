package dev.nonamecrackers2.simpleclouds.mixin;

import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Extend the world projection to the cloud field, as the original getDepthFar hook did. */
@Mixin(Camera.class)
public abstract class MixinCamera {
    @Shadow private float depthFar;
    @Unique private static boolean simpleclouds$loggedFarPlane;

    @ModifyArg(method = "update", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;setupPerspective(FFFFF)V"),
            index = 1, require = 1)
    private float simpleclouds$extendCloudFarPlane(float vanillaFar) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !SimpleCloudsRenderer.canRenderInDimension(mc.level))
            return vanillaFar;
        float cloudFar = SimpleCloudsRenderer.getOptionalInstance()
                .map(SimpleCloudsRenderer::getFogEnd).orElse(0.0F);
        if (!Float.isFinite(cloudFar) || cloudFar <= 0.0F)
            return vanillaFar;
        this.depthFar = Math.max(vanillaFar, cloudFar);
        if (!simpleclouds$loggedFarPlane && this.depthFar > vanillaFar) {
            simpleclouds$loggedFarPlane = true;
            org.apache.logging.log4j.LogManager.getLogger("simpleclouds/FarPlane")
                    .info("Extended camera far plane from {} to {} blocks", vanillaFar, this.depthFar);
        }
        return this.depthFar;
    }
}

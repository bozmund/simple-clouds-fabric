package dev.nonamecrackers2.simpleclouds.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.joml.Vector3f;

/** Original sky color adjustment at the 26.3 render-state extraction boundary. */
@Mixin(SkyRenderer.class)
public class MixinSkyRenderer {
    @Inject(method="extractRenderState", at=@At("TAIL"), require=1)
    private void simpleclouds$stormSky(ClientLevel level,float partialTick,Camera camera,
            SkyRenderState state,CallbackInfo ci) {
        if (state.skyColor == null || !SimpleCloudsRenderer.canRenderInDimension(level)) return;
        SimpleCloudsRenderer.getOptionalInstance().ifPresent(renderer -> {
            var source=state.skyColor;
            var color=renderer.getWorldEffectsManager().calculateSkyColor(source.x(),source.y(),source.z(),partialTick);
            state.skyColor=new Vector3f(color.getRed()/255.0F,color.getGreen()/255.0F,color.getBlue()/255.0F);
        });
    }
}

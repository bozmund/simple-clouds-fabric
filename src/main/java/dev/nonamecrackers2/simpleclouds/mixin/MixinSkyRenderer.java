package dev.nonamecrackers2.simpleclouds.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.Minecraft;
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
    @Shadow @Final private RenderTarget renderTarget;
    private static boolean simpleclouds$atmosphereErrorLogged;

    @Inject(method="render(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lnet/minecraft/client/renderer/state/level/SkyRenderState;)V", at=@At("TAIL"), require=1)
    private void simpleclouds$afterSky(GpuBufferSlice fog, SkyRenderState state, CallbackInfo ci) {
        // Only the primary sky target; never another renderer's offscreen view.
        if (this.renderTarget != Minecraft.getInstance().gameRenderer.mainRenderTarget() || state.skyColor == null) return;
        try {
            SimpleCloudsRenderer.getOptionalInstance().ifPresent(renderer -> {
                var mc = Minecraft.getInstance();
                var pos = mc.gameRenderer.mainCamera().position();
                var stack = new com.mojang.blaze3d.vertex.PoseStack();
                stack.last().pose().set(mc.gameRenderer.mainCamera().getViewRotationMatrix(new org.joml.Matrix4f()));
                var projection = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.projectionMatrix;
                float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
                renderer.renderAfterSky(stack, projection, partialTick, pos.x, pos.y, pos.z);
                if ("1".equals(System.getenv("SIMPLECLOUDS_DEV")) && "1".equals(System.getenv("SIMPLECLOUDS_TEST_REPEAT_SKY"))) {
                    renderer.renderAfterSky(stack, projection, partialTick, pos.x, pos.y, pos.z);
                    renderer.verifyRepeatedSkyStage();
                }
            });
        } catch (Throwable error) {
            if (!simpleclouds$atmosphereErrorLogged) {
                simpleclouds$atmosphereErrorLogged=true;
                org.apache.logging.log4j.LogManager.getLogger("simpleclouds/MixinSkyRenderer")
                        .error("Simple Clouds: after-sky cloud pass failed (further failures suppressed)", error);
            }
        }
    }
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

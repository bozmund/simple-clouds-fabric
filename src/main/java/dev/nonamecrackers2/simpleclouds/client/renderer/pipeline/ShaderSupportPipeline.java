package dev.nonamecrackers2.simpleclouds.client.renderer.pipeline;

import org.joml.Matrix4f;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;

/** Original late-stage ownership; actual Iris pack/depth compatibility requires runtime validation. */
public class ShaderSupportPipeline implements CloudsRenderPipeline {
 protected ShaderSupportPipeline() {}
 @Override public void prepare(Minecraft mc, SimpleCloudsRenderer renderer, PoseStack stack, Matrix4f projection,
   float partialTick, double x, double y, double z, Frustum frustum) {}
 @Override public void afterSky(Minecraft mc, SimpleCloudsRenderer renderer, PoseStack stack, Matrix4f projection,
   float partialTick, double x, double y, double z, Frustum frustum) {}
 @Override public void beforeWeather(Minecraft mc, SimpleCloudsRenderer renderer, PoseStack stack, Matrix4f projection,
   float partialTick, double x, double y, double z, Frustum frustum) {}
 @Override public void afterLevel(Minecraft mc, SimpleCloudsRenderer renderer, PoseStack stack, Matrix4f projection,
   float partialTick, double x, double y, double z, Frustum frustum) {
  renderer.renderCloudsAfterShaderLevel(stack, projection, partialTick, x, y, z);
 }
}

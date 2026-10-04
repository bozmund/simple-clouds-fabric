package dev.nonamecrackers2.simpleclouds.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.nonamecrackers2.simpleclouds.client.event.impl.DetermineCloudRenderPipelineEvent;
import dev.nonamecrackers2.simpleclouds.client.renderer.pipeline.CloudsRenderPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Matrix4f;

/** Explicit development event override probe; production never registers it. */
public final class PipelineLifecycleProbe {
 private static boolean installed, verified;
 public static void install() {
  if(installed || !"1".equals(System.getenv("SIMPLECLOUDS_DEV"))
    || !"1".equals(System.getenv("SIMPLECLOUDS_TEST_PIPELINE_API"))) return;
  installed=true; MinecraftForge.EVENT_BUS.register(new PipelineLifecycleProbe());
 }
 @SubscribeEvent public void select(DetermineCloudRenderPipelineEvent event) {
  boolean late="1".equals(System.getenv("SIMPLECLOUDS_TEST_SHADER_STAGE"));
  CloudsRenderPipeline delegate=late ? CloudsRenderPipeline.SHADER_SUPPORT : event.getRenderPipeline();
  event.overridePipeline(new CloudsRenderPipeline() {
   int stage, prepares;
   @Override public void prepare(Minecraft mc,SimpleCloudsRenderer r,PoseStack s,Matrix4f p,float t,double x,double y,double z,Frustum f) {
    if(++prepares!=1 || s==null || p==null || f==null)
     throw new IllegalStateException("Simple Clouds ERROR: pipeline prepare contract failed");
    stage=1; delegate.prepare(mc,r,s,p,t,x,y,z,f);
   }
   @Override public void afterSky(Minecraft mc,SimpleCloudsRenderer r,PoseStack s,Matrix4f p,float t,double x,double y,double z,Frustum f) {
    if(stage!=1 && stage!=2) throw new IllegalStateException("Simple Clouds ERROR: afterSky before prepare");
    stage=2; delegate.afterSky(mc,r,s,p,t,x,y,z,f);
    if(late && r.getCompletedCloudStagesThisFrame()!=0)
     throw new IllegalStateException("Simple Clouds ERROR: shader-stage clouds rendered before terrain");
   }
   @Override public void beforeWeather(Minecraft mc,SimpleCloudsRenderer r,PoseStack s,Matrix4f p,float t,double x,double y,double z,Frustum f) {
    if(stage!=2 && stage!=3) throw new IllegalStateException("Simple Clouds ERROR: beforeWeather before afterSky");
    stage=3; delegate.beforeWeather(mc,r,s,p,t,x,y,z,f);
   }
   @Override public void afterLevel(Minecraft mc,SimpleCloudsRenderer r,PoseStack s,Matrix4f p,float t,double x,double y,double z,Frustum f) {
    delegate.afterLevel(mc,r,s,p,t,x,y,z,f);
    if(late && stage==2 && !verified && r.getCompletedCloudStagesThisFrame()==1 && r.getWorldFogDrawsThisFrame()==0) {
     verified=true;
     org.apache.logging.log4j.LogManager.getLogger("simpleclouds/PipelineProbe")
       .info("[PIPELINE-API] late stage verified earlyClouds=0 lateClouds=1 defaultFog=0");
    }
    if(stage==3 && !verified && r.getWorldFogDrawsThisFrame()==1) {
     verified=true;
     org.apache.logging.log4j.LogManager.getLogger("simpleclouds/PipelineProbe")
       .info("[PIPELINE-API] event override verified prepare=1 afterSky beforeWeather afterLevel actualFog=1");
    }
    stage=4;
   }
  });
 }
}

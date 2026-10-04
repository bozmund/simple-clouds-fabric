import dev.nonamecrackers2.simpleclouds.client.renderer.v2.OriginalWorldFogUniforms;
import java.nio.*;
import java.nio.file.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Numerical packing/unprojection and source regressions, not visual parity proof. */
public class OriginalWorldFogTest {
 static void require(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
 public static void main(String[] args) throws Exception {
  ByteBuffer out=ByteBuffer.allocateDirect(160).order(ByteOrder.nativeOrder()); int cases=0;
  for(float aspect:new float[]{1,16f/9,21f/9}) for(float yaw:new float[]{0,.7f,2})
   for(float pitch:new float[]{-.6f,0,.6f}) for(float roll:new float[]{-.35f,0,.35f}) {
    Matrix4f projection=new Matrix4f().setPerspective((float)Math.toRadians(70),aspect,30000,.05f,true);
    projection.m20(.02f).m21(-.01f);
    Matrix4f rotation=new Matrix4f().rotateX(pitch).rotateY(yaw).rotateZ(roll);
    Matrix4f translated=new Matrix4f(rotation).translate(-12345,-70,9191);
    Matrix4f beforeP=new Matrix4f(projection),beforeV=new Matrix4f(translated);
    OriginalWorldFogUniforms.write(out,projection,translated,128,512,new float[]{.1f,.2f,.3f},true);
    require(projection.equals(beforeP)&&translated.equals(beforeV),"Mutated matrices");
    require(out.getFloat(128)==.1f&&out.getFloat(136)==.3f&&out.getFloat(144)==128&&out.getFloat(148)==512&&out.getInt(152)==1&&out.getInt(156)==1,"std140 packing");
    Matrix4f invP=new Matrix4f().set(0,out),invV=new Matrix4f().set(64,out);
    for(float distance:new float[]{.1f,128,1024}) for(float x:new float[]{-.9f,0,.9f}) {
     Vector4f view=new Vector4f(x*distance,.2f*distance,-distance,1);
     Vector4f expected=new Vector4f(view).mul(new Matrix4f(rotation).invert());
     Vector4f clip=new Vector4f(view).mul(projection); clip.div(clip.w);
     Vector4f recovered=new Vector4f(clip).mul(invP); recovered.div(recovered.w).mul(invV);
     require(new Vector3f(recovered.x,recovered.y,recovered.z).distance(new Vector3f(expected.x,expected.y,expected.z))<.001f,"Camera-relative depth reconstruction");
     cases++;
    }
   }
  OriginalWorldFogUniforms.write(out,new Matrix4f(),new Matrix4f(),Float.MAX_VALUE/2,Float.MAX_VALUE,new float[]{1,1,1},false);
  require(out.getInt(156)==0,"Stale storm state");
  String base="src/main/java/dev/nonamecrackers2/simpleclouds/client/renderer/";
  String pipeline=Files.readString(Path.of(base+"v2/CloudsDrawPipeline.java"));
  String renderer=Files.readString(Path.of(base+"SimpleCloudsRenderer.java"));
  String shader=Files.readString(Path.of("src/main/resources/assets/simpleclouds/shaders/core/original_world_fog.fsh"));
  require(shader.contains("depth <= cloudDepth")&&shader.contains("texture(PreCloudDepthSampler")&&!shader.contains("depth*2.0-1.0"),"Legacy depth convention returned");
  require(pipeline.contains("this.cloudDepthSnapshot.copyDepthFrom(main)")&&pipeline.contains("this.cloudDepthReady=false")&&pipeline.contains("this.stormFogReady=false"),"Missing current-frame snapshot lifetime");
  require(pipeline.contains("pass.setUniform(\"DiffuseSampler\",this.worldFogSource.getColorTextureView()"),"Framebuffer feedback");
  int late=renderer.indexOf("public void renderAfterLevel(float");
  String stage=renderer.substring(late,renderer.indexOf("public void renderAtmosphereAfterSky",late));
  int defaultStart=stage.indexOf("if (this.drawPipeline == null) return;");
  require(defaultStart>=0,"Missing default-stage readiness boundary");
  String defaultStage=stage.substring(defaultStart);
  require(defaultStage.indexOf("this.renderBeforeWeather(")>=0&&defaultStage.indexOf("this.renderBeforeWeather(")<defaultStage.indexOf(".renderRain("),"Default fog hook not before custom weather");
  String latePipelineStage=stage.substring(0,defaultStart);
  require(latePipelineStage.contains("this.renderAfterLevel(lateStack,")&&!latePipelineStage.contains("this.renderBeforeWeather("),"Late pipeline accidentally executes default post-fog");
  String api=Files.readString(Path.of(base+"pipeline/DefaultPipeline.java"));
  require(api.contains("FogType.NONE")&&api.contains("FogRenderMode.SCREEN_SPACE")&&api.contains("renderer.doScreenSpaceWorldFog(stack, projMat, partialTick)"),"Original default-pipeline guard/call missing");
  require(renderer.contains("this.getRenderPipeline().beforeWeather(this.mc, this, stack, projMat"),"Public hook still dead");
  require(renderer.contains("drawWorldFog(projMat, stack.last().pose()"),"Supplied camera stack ignored");
  require(renderer.contains("this.worldFogApplied = false")&&renderer.contains("|| this.worldFogApplied")&&renderer.contains("this.worldFogApplied = this.drawPipeline.drawWorldFog"),"Fog duplicate guard or successful-draw tracking missing");
  System.out.println("PASS original world-fog std140 and "+cases+" reversed-Z relative positions; source lifetime/order checks; visual GPU tests separate");
 }
}

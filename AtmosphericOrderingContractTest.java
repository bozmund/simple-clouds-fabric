import java.nio.file.Files;
import java.nio.file.Path;

/** Source regression only: runtime frame-graph/composition tests remain required. */
public class AtmosphericOrderingContractTest {
 static void require(boolean value,String message) { if(!value) throw new AssertionError(message); }
 public static void main(String[] args) throws Exception {
  String base="src/main/java/dev/nonamecrackers2/simpleclouds/";
  String hook=Files.readString(Path.of(base+"mixin/MixinSkyRenderer.java"));
  String renderer=Files.readString(Path.of(base+"client/renderer/SimpleCloudsRenderer.java"));
  String shader=Files.readString(Path.of("src/main/resources/assets/simpleclouds/shaders/core/atmospheric_clouds.fsh"));
  String pipeline=Files.readString(Path.of(base+"client/renderer/v2/CloudsDrawPipeline.java"));
  require(hook.contains("method=\"render(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lnet/minecraft/client/renderer/state/level/SkyRenderState;)V\"") && hook.contains("at=@At(\"TAIL\"), require=1"),"Missing exact after-sky hook");
  require(hook.contains("this.renderTarget != Minecraft.getInstance().gameRenderer.mainRenderTarget()"),"Missing primary-target guard");
  require(hook.contains("renderer.renderAfterSky(")&&!hook.contains("renderer.renderCloudsAfterSky("),"Sky hook bypasses pipeline API");
  String defaults=Files.readString(Path.of(base+"client/renderer/pipeline/DefaultPipeline.java"));
  require(defaults.contains("renderer.renderCloudsAfterSky(stack, projMat, partialTick, camX, camY, camZ)"),"Default afterSky doesn't route live GPU stage");
  int api=renderer.indexOf("public void renderAfterSky(");
  String wrapper=renderer.substring(api,renderer.indexOf("public void renderBeforeWeather(",api));
  require(wrapper.contains("getRenderPipeline().afterSky(")&&wrapper.contains("finally")&&wrapper.contains("profiler.pop()"),"Original API route/profiler cleanup missing");
  require(hook.contains("cameraRenderState.projectionMatrix")&&hook.contains("getViewRotationMatrix"),"Sky API gets null view/projection inputs");
  int sky=renderer.indexOf("public void renderCloudsAfterSky(");
  int preview=renderer.indexOf("private boolean drawPreviewInWorld(",sky);
  require(sky>=0 && preview>sky,"Missing world/preview stage boundaries");
  String skyStage=renderer.substring(sky,preview);
  require(skyStage.indexOf("this.worldCloudStageStarted")<skyStage.indexOf("this.drawPipeline.beginFrame()"),"Repeated sky API resets draw resources");
  require(renderer.contains("this.worldCloudStageStarted = false;")&&renderer.contains("this.worldCloudStageExecutions = 0;"),"Sky ownership persists across frames");
  require(renderer.contains("DetermineCloudRenderPipelineEvent(selected)")&&renderer.contains("MinecraftForge.EVENT_BUS.post(selection)")&&renderer.contains("selection.getOverridenPipeline()"),"Live pipeline event/override selection missing");
  require(renderer.contains("CompatHelper.areShadersRunning()"),"Shader selection ignores actual provider");
  String provider=Files.readString(Path.of("src/main/java/nonamecrackers2/crackerslib/common/compat/CompatHelper.java"));
  require(provider.contains("isModLoaded(\"oculus\") || isModLoaded(\"iris\")"),"Fabric Iris is excluded by legacy Oculus detector");
  String fullpack=Files.readString(Path.of("tools/fullpack-coverage-test.sh"));
  require(fullpack.contains("before/iris.properties")&&fullpack.contains("cmp -s \"$evidence/before/iris.properties\""),"Shader test doesn't restore exact Iris config");
  require(fullpack.contains("Close the existing render pass before performing additional commands"),"Shader acceptance ignores observed open-pass failure");
  require(renderer.contains("this.pipelinePrepared = false;")&&renderer.contains("if (this.pipelinePrepared) return;"),"Prepare not once per frame");
  String shaderRoute=Files.readString(Path.of(base+"client/renderer/pipeline/ShaderSupportPipeline.java"));
  require(shaderRoute.contains("renderer.renderCloudsAfterShaderLevel("),"Shader afterLevel remains a no-op");
  require(skyStage.contains("renderCloudStage(false")&&skyStage.contains("if (atmospheric) this.renderAtmosphereAfterSky();"),"Late stage replaces shader sky");
  require(skyStage.indexOf("this.renderAtmosphereAfterSky();")<skyStage.indexOf("this.generateAndDrawClouds("),"Atmosphere not below voxel clouds");
  String levelHook=Files.readString(Path.of(base+"mixin/MixinLevelRenderer.java"));
  require(levelHook.contains("SimpleCloudsRenderer::beginWorldRenderFrame")&&levelHook.contains(".renderAfterLevel("),"Missing frame reset/late effect hook");
  require(!levelHook.contains(".renderCloudsAfterSky(")&&!levelHook.contains(".renderBeforeLevel("),"Cloud geometry redrawn after terrain");
  require(levelHook.contains("CompatHelper.areShadersRunning()) return;"),"Shader stage still races Iris LevelRenderer finalization");
  String gameHook=Files.readString(Path.of(base+"mixin/MixinGameRenderer.java"));
  require(gameHook.contains("method = \"renderLevel()V\"")&&gameHook.contains("require = 1")&&gameHook.contains("renderer.get().renderAfterLevel("),"Missing exact post-Iris enclosing world hook");
    require(gameHook.contains("GameRenderer;render3dHud(")&&gameHook.contains("shift = At.Shift.BEFORE"),"Late world stage must precede HUD projection/depth replacement");
    require(renderer.contains("Matrix4f lateWeatherView = new Matrix4f(lateStack.last().pose())")&&renderer.contains("renderRain(lateWeatherView,")&&renderer.contains("renderLightning(lateWeatherView,"),"Shader weather missing absolute-world camera transform");
  require(gameHook.contains("if (!nonamecrackers2.crackerslib.common.compat.CompatHelper.areShadersRunning()) return;"),"Default stage duplicated at enclosing world boundary");
  require(fullpack.contains("Refusing to overwrite replacement DH JAR")&&fullpack.contains("restored-dh.sha256"),"DH diagnostic doesn't preserve/verify exact user artifact");
  int call=renderer.indexOf("this.atmosphericClouds.render(");
  require(call>renderer.indexOf("public void renderAtmosphereAfterSky()") && call==renderer.lastIndexOf("this.atmosphericClouds.render("),"Atmosphere duplicated or returned to end-of-level pass");
  require(!shader.contains("DepthSampler")&&!shader.contains("discard;"),"Late scene depth mask restored");
  String atmosphere=pipeline.substring(pipeline.indexOf("this.atmosphericPipeline ="),pipeline.indexOf("this.lightningPipeline ="));
  require(atmosphere.contains("withColorTargetState(ColorTargetState.DEFAULT)"),"Atmosphere blending differs from original replacement pass");
  require(shader.contains("vec4(texture(DiffuseSampler, texCoord).rgb, 1.0)"),"Atmosphere output no longer opaque scene composite");
  System.out.println("PASS atmospheric source ordering/primary target/no depth mask/no blend; runtime validation separate");
 }
}

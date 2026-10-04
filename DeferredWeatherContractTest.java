import java.nio.file.*;

/** Source regressions only; real classic/OIT, rain/snow visual tests remain required. */
public class DeferredWeatherContractTest {
 static void require(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
 public static void main(String[] args) throws Exception {
  String base="src/main/java/dev/nonamecrackers2/simpleclouds/";
  String renderer=Files.readString(Path.of(base+"client/renderer/SimpleCloudsRenderer.java"));
  String hook=Files.readString(Path.of(base+"mixin/MixinWeatherEffectRenderer.java"));
  String pipeline=Files.readString(Path.of(base+"client/renderer/v2/CloudsDrawPipeline.java"));
  require(hook.contains("if (SimpleCloudsRenderer.isRenderingDeferredWeather()) return false;"),"Replay cancels itself");
  require(hook.indexOf("captureWeatherState(state)")!=hook.lastIndexOf("captureWeatherState(state)"),"Classic/OIT capture missing");
  require(hook.contains("Lnet/minecraft/client/renderer/oit/OitStage;")&&hook.contains("require = 1"),"Missing exact required weather hook");
  int reset=renderer.indexOf("public void beginWorldRenderFrame()");
  String begin=renderer.substring(reset,renderer.indexOf("public void renderAfterLevel(float",reset));
  require(begin.contains("capturedWeatherThisFrame = false")&&begin.contains("CAPTURED_WEATHER.reset()"),"Prior-frame precipitation leaks");
  int replay=renderer.indexOf("private void drawVanillaWeatherAfterClouds");
  String draw=renderer.substring(replay,renderer.indexOf("private void closeOriginalGenerator",replay));
  require(draw.contains("renderingDeferredWeather = true")&&draw.contains("finally")&&draw.contains("renderingDeferredWeather = false"),"Replay bypass leaks on failure");
  int nativeDraw=pipeline.indexOf("public void drawVanillaWeather(");
  String pass=pipeline.substring(nativeDraw,pipeline.indexOf("public void beginTransparency",nativeDraw));
  require(pass.indexOf("weather.prepare(")<pass.indexOf("encoder.createRenderPass("),"Weather upload inside open pass");
  require(pass.contains("setTranslation(0,0,0)")&&pass.contains("modelView.popMatrix()"),"Camera translated twice or stack leaks");
  require(!pass.contains("setUniform(\"DynamicTransforms\""),"Caller transform silently overridden by native renderer");
  require(renderer.contains("if (SimpleCloudsCompatHelper.renderCustomRain())\n\t\t\tthis.getWorldEffectsManager().renderRain"),"Custom rain draw bypasses original Particle Rain ownership policy");
  require(pass.indexOf("weather.render(state, pass)")<pass.indexOf("vanillaWeatherReplays++"),"Replay telemetry precedes actual native draw");
  int frameReset=pipeline.indexOf("public void beginFrame(");
  require(pipeline.substring(frameReset).contains("vanillaWeatherReplays=0"),"Replay count persists across frames");
  String fixture=Files.readString(Path.of(base+"client/DevShot.java"));
  require(fixture.contains("weatherCycleScratchWorld = name.equals(\"CodexWeatherCycle20261001\")"),"Weather cycle can mutate an ordinary world");
  require(fixture.contains("weatherCycleScratchWorld && \"1\".equals(System.getenv(\"SIMPLECLOUDS_DEV\"))"),"Weather cycle lacks developer guard");
  require(fixture.contains("SIMPLECLOUDS_TEST_WEATHER_CYCLE"),"Weather cycle lacks explicit opt-in");
  int cycle=fixture.indexOf("private static void changeWeatherCycle");
  String mutate=fixture.substring(cycle,fixture.indexOf("private static void observeWeatherCycle",cycle));
  require(mutate.contains("server.execute(")&&mutate.contains("SyncType.CLOUD_FORMATIONS"),"Weather cycle does not update authoritative server formations");
  require(!mutate.contains("setRainLevel")&&!mutate.contains("DEBUG_FORCE_WEATHER"),"Weather cycle fabricates precipitation instead of testing local weather");
  String harness=Files.readString(Path.of("tools/isolated-motion-test.sh"));
  require(harness.contains("clear verified rain=0.0 replays=0")&&harness.contains("wet verified"),"Weather-cycle runtime success lacks both phase gates");
  require(fixture.contains("pauseScratchWorld = name.equals(\"CodexPause20261001\")")&&fixture.contains("SIMPLECLOUDS_TEST_PAUSE"),"Pause probe isn't restricted to opted-in scratch world");
  require(fixture.contains("mc.pauseGame(false)")&&fixture.contains("server.isPaused()"),"Pause probe simulates pause instead of opening actual singleplayer menu");
  require(fixture.contains("renderer.getPublishedBatchCount()!=pausedCycles")&&fixture.contains("mc.level.getGameTime()!=pausedTick"),"Pause probe lacks GPU/world-time invariants");
  require(harness.contains("frozen verified")&&harness.contains("resume verified"),"Pause runtime gate accepts only half the lifecycle");
  require(harness.contains("cmp -s run/screenshots/devshot-PAUSE-START.png run/screenshots/devshot-PAUSE.png"),"Pause runtime gate ignores visible animation");
  require(fixture.contains("waterScratchWorld = name.equals(\"CodexWater20261001\")")&&fixture.contains("SIMPLECLOUDS_TEST_WATER"),"Water fixture lacks scratch-world/explicit opt-in restriction");
  require(fixture.contains("FogType.WATER && fog==0")&&fixture.contains("FogType.NONE && fog==1"),"Water fixture doesn't observe real fluid and fog execution");
  require(harness.contains("above verified")&&harness.contains("submerged verified")&&harness.contains("exit verified"),"Water runtime gate omits a transition phase");
  System.out.println("PASS deferred-weather source gates, classic/OIT capture, frame reset, replay finally, native camera ownership; runtime tests separate");
 }
}

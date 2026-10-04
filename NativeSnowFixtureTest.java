import java.nio.file.*;

/** Fixture safety/coverage regressions only. Actual snow remains a runtime gate. */
public class NativeSnowFixtureTest {
 static void require(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
 public static void main(String[] args) throws Exception {
  int covered=0;
  for(int bottom=-64;bottom<=120;bottom+=16) {
   int top=Math.min(bottom+15,120);
   require(33L*33*(top-bottom+1)<=32768,"Command exceeds block-volume limit");
   covered+=top-bottom+1;
  }
  require(covered==185,"Biome vertical coverage has holes");
  String dev=Files.readString(Path.of("src/main/java/dev/nonamecrackers2/simpleclouds/client/DevShot.java"));
  require(dev.contains("nativeSnowScratchWorld && \"1\".equals(System.getenv(\"SIMPLECLOUDS_TEST_SNOW\"))"),"Biome mutation not scratch-world gated");
  require(dev.contains("nativeSnowScratchWorld = name.equals(\"CodexNativeSnow20261001\") || name.equals(\"CodexSnowRoof20261001\");"),"Fixture world selection broadened");
  require(dev.contains("catch(com.mojang.brigadier.exceptions.CommandSyntaxException error)"),"Fixture errors hidden");
  require(dev.contains("if(error.getType()!=net.minecraft.server.commands.FillBiomeCommand.ERROR_NO_BIOMES_SET) throw error"),"Idempotent fixture swallows real command errors");
  String tool=Files.readString(Path.of("tools/isolated-motion-test.sh"));
  require(tool.contains("snow=[1-9]")&&tool.contains("native snow fixture center=.*biome=.*minecraft:snowy_plains"),"Capture alone accepted as snow proof");
  require(tool.contains("purecustomsnow") && tool.contains("texture=.*snow.*quads=[1-9]"),"Custom snow accepts native-only proof");
  require(!tool.contains("purecustomsnow ]]; then vanilla_weather_test=1"),"Custom snow inadvertently uses native weather");
  require(dev.contains("centerX=Mth.floor(snowView.x), centerZ=Mth.floor(snowView.z)"),"Snow fixture assumes world-origin camera");
  System.out.println("PASS snow fixture block volume, complete vertical coverage, scratch-world selection and evidence gates; rendered behavior separate");
 }
}

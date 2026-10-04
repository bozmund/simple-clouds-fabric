import dev.nonamecrackers2.simpleclouds.client.renderer.rain.PrecipitationQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class PrecipitationParityTest {
    private static int checks;
    private static void near(double actual, double expected, String label) {
        checks++;
        // Original Mth sine lookup differs slightly from JOML's quaternion trig.
        // Five thousandths of a block bounds that error over a 32-block streak.
        if (!Double.isFinite(actual) || Math.abs(actual-expected)>.005)
            throw new AssertionError(label+": "+actual+" != "+expected);
    }
    public static void main(String[] args) throws Exception {
        for(boolean transparency : new boolean[]{false,true}) for(boolean shaders : new boolean[]{false,true}) {
            boolean actual=dev.nonamecrackers2.simpleclouds.client.renderer.v2.OriginalPrecipitationDepth.writesDepth(transparency,shaders);
            if(actual!=(transparency || shaders)) throw new AssertionError("original conditional depthMask changed");
            checks++;
        }
        String effects=java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/dev/nonamecrackers2/simpleclouds/client/renderer/WorldEffects.java"));
        int spawn=effects.indexOf("this.drops.put(key, drop);");

        if(!effects.contains("Biome cameraBiome = level.getBiome(camPos).value();")
            || !effects.contains("cameraBiome.hasPrecipitation()")
            || !effects.contains("cameraBiome.getPrecipitationAt(pos, level.getSeaLevel())")
            || effects.contains("level.getBiome(pos).value().getPrecipitationAt"))
            throw new AssertionError("original camera-biome precipitation ownership lost");
        int reap=effects.indexOf("Iterator<Map.Entry<Long, PrecipitationQuad>> it");
        if(spawn<0 || reap<spawn || effects.substring(spawn,reap).contains("drop.tick()"))
            throw new AssertionError("original spawn-before-reap/one-age lifecycle lost");
        if(!effects.contains("BlockPos pos = drop.getBlockPos();") || effects.contains("box.contains(pos.x + 0.5D"))
            throw new AssertionError("scan centers use centered coordinates twice");
        String draw=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/dev/nonamecrackers2/simpleclouds/client/renderer/v2/RainDrawPipeline.java"));
        String vs=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/assets/simpleclouds/shaders/core/rain.vsh"));
        String fs=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/assets/simpleclouds/shaders/core/rain.fsh"));
        if(!vs.contains("Position - CameraPosition") || !vs.contains("max(length(relativePosition.xz), abs(relativePosition.y))")
            || !fs.contains("smoothstep(FogEnvironmentalStart") || !fs.contains("smoothstep(FogRenderDistanceStart")
            || !fs.contains("max(environmentFog, distanceFog) * FogColor.a") || !fs.contains(", color.a)"))
            throw new AssertionError("original particle fog curve/camera distances/alpha preservation lost");
        if(!draw.contains("optionsRenderState.improvedTransparency") || !draw.contains("writeDepth ? this.depthWritePipeline : this.pipeline")
            || !draw.contains("CompareOp.GREATER_THAN_OR_EQUAL, writeDepth"))
            throw new AssertionError("real rain must select depth policy without disabling roof test");
        if(!effects.contains("LightCoordsUtil.getLightCoords(this.mc.level, drop.getBlockPos())")
            || !draw.contains("gameRenderer.levelLightmap()") || !vs.contains("sample_lightmap(Sampler2, UV2)")
            || fs.contains("SceneColor") || !fs.contains("tex * vertexColor"))
            throw new AssertionError("precipitation must use actual position lightmap, not screen tint/fullbright");
        for(int block=0;block<16;block++) for(int sky=0;sky<16;sky++) {
            int packed=net.minecraft.util.LightCoordsUtil.pack(block,sky);
            near(packed & 0xffff,block*16,"packed block UV2");
            near((packed >>> 16)&0xffff,sky*16,"packed sky UV2");
        }
        for(int x : new int[]{-3000,-1,0,1,3000}) {
            BlockPos pos=new BlockPos(x,80,-3);
            PrecipitationQuad edge=new PrecipitationQuad(Biome.Precipitation.RAIN,context ->
                BlockHitResult.miss(context.getTo(),Direction.UP,BlockPos.ZERO),pos,0,0,60,2);
            var box=new net.minecraft.world.phys.AABB(x,80,-3,x+1,81,-2);
            var integer=edge.getBlockPos();
            if(!box.contains(integer.getX()+.5,integer.getY()+.5,integer.getZ()+.5))
                throw new AssertionError("last scan cell excluded");
            if(box.contains(edge.getPos().x+.5,edge.getPos().y+.5,edge.getPos().z+.5))
                throw new AssertionError("fixture must distinguish double-centered boundary");
            checks+=2;
        }
        for (float pitch : new float[]{0,.1F,.4F}) for (float yaw : new float[]{0,.8F,-2F}) {
            Vec3[] end = new Vec3[1];
            PrecipitationQuad rain = new PrecipitationQuad(Biome.Precipitation.RAIN, context -> {
                end[0]=context.getTo();
                return BlockHitResult.miss(context.getTo(), Direction.UP, BlockPos.ZERO);
            }, new BlockPos(2,80,-3), pitch, yaw, 60, 2);
            for (int i=0;i<20;i++) rain.tick();
            near(rain.getLength(),32,"unobstructed length");
            float[] v=rain.vertices(1,10,60,10);
            // The midpoint of the rendered bottom must exactly reach the raycast endpoint.
            near((v[10]+v[15])/2, end[0].x,"ray/render X alignment");
            near((v[11]+v[16])/2, end[0].y,"ray/render Y alignment");
            near((v[12]+v[17])/2, end[0].z,"ray/render Z alignment");
            near(v[3],0,"original U strip left");
            near(v[8],1,"original U strip right");
            near(v[4],-2.1,"rain scroll");
        }
        PrecipitationQuad snow = new PrecipitationQuad(Biome.Precipitation.SNOW, context ->
            new BlockHitResult(context.getFrom().add(0,-5,0),Direction.UP,BlockPos.ZERO,false),
            BlockPos.ZERO,0,0,60,4);
        snow.tick();
        near(snow.getLength(),5,"roof clips streak length");
        float[] initial=snow.vertices(0,10,0,10);
        near(initial[3],.5,"fade begins at zero width");
        for(int i=1;i<20;i++) snow.tick();
        near(snow.vertices(1,10,0,10)[4],-.21,"snow scroll ten times slower");
        for(int i=20;i<60;i++) snow.tick();
        float[] faded=snow.vertices(1,10,0,10);
        near(faded[3],.5,"fade ends at zero width");
        if(snow.isDead()) throw new AssertionError("original lifetime ends after tick 60");
        snow.tick();
        if(!snow.isDead()) throw new AssertionError("expired precipitation remains alive");
        System.out.println("PASS: "+checks+" precipitation checks (wind ray/render alignment, shelter, rain/snow UVs, width fade, lifespan)");
    }
}

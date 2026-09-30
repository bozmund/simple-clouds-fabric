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
    public static void main(String[] args) {
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

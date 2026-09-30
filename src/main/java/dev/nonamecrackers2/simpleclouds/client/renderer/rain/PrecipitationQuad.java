package dev.nonamecrackers2.simpleclouds.client.renderer.rain;

import java.util.Map;
import java.util.function.Function;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Original precipitation simulation, independent of the render backend. */
public class PrecipitationQuad {
    public static final float MAX_LENGTH = 32.0F;
    public static final float MAX_WIDTH = 2.0F;
    public static final Map<Biome.Precipitation, Identifier> TEXTURE_BY_PRECIPITATION = Map.of(
        Biome.Precipitation.RAIN, Identifier.parse("minecraft:textures/environment/rain.png"),
        Biome.Precipitation.SNOW, Identifier.parse("minecraft:textures/environment/snow.png"));
    private final Biome.Precipitation precipitation;
    private final Function<ClipContext, BlockHitResult> raycaster;
    private final BlockPos blockPos;
    private final Vector3f position;
    private final int lifeSpan;
    private final float initialWidth;
    private float length = MAX_LENGTH;
    private float xRot, yRot, widthO, width;
    private int tickCount;

    public PrecipitationQuad(Biome.Precipitation precipitation,
            Function<ClipContext, BlockHitResult> raycaster, BlockPos position,
            float xRot, float yRot, int lifeSpan, float initialWidth) {
        if (precipitation == Biome.Precipitation.NONE)
            throw new IllegalArgumentException("Cannot be NONE precipitation type");
        this.precipitation = precipitation;
        this.raycaster = raycaster;
        this.blockPos = position.immutable();
        this.position = new Vector3f(position.getX()+.5F, position.getY()+.5F, position.getZ()+.5F);
        this.xRot = xRot;
        this.yRot = yRot;
        this.lifeSpan = lifeSpan;
        this.initialWidth = Math.max(.1F, initialWidth);
    }
    public Biome.Precipitation getPrecipitation() { return precipitation; }
    public Vector3f getPos() { return position; }
    public BlockPos getBlockPos() { return blockPos; }
    public float getLength() { return length; }
    public float getXRot() { return xRot; }
    public float getYRot() { return yRot; }
    public void setXRot(float rot) { xRot = rot; }
    public void setYRot(float rot) { yRot = rot; }
    public int getTickCount() { return tickCount; }
    public boolean isDead() { return tickCount > lifeSpan; }

    public void tick() {
        tickCount++;
        Vec3 start = new Vec3(position);
        float yaw = -yRot, pitch = xRot - (float)Math.PI / 2.0F;
        float pitchCos = Mth.cos(pitch);
        Vec3 end = new Vec3(Mth.sin(yaw)*pitchCos, Mth.sin(pitch), Mth.cos(yaw)*pitchCos)
            .scale(MAX_LENGTH).add(start);
        BlockHitResult hit = raycaster.apply(new ClipContext(start, end,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, net.minecraft.world.phys.shapes.CollisionContext.empty()));
        length = (float)start.distanceTo(hit.getLocation());
        widthO = width;
        width = initialWidth * Math.min(1.0F,
            (tickCount < lifeSpan-20 ? tickCount : lifeSpan-tickCount) / 20.0F);
    }

    /** Four world-space XYZUV vertices, preserving the original pose/UV equations. */
    public float[] vertices(float partialTick, double camX, double camY, double camZ) {
        Quaternionf inverse = new Quaternionf().rotateX(xRot).rotateY(yRot);
        Vector3f relativeCamera = new Vector3f((float)camX, (float)camY, (float)camZ)
            .sub(position).rotate(inverse);
        float angle = (float)Mth.atan2(-relativeCamera.x, -relativeCamera.z);
        Quaternionf rotation = inverse.invert().rotateY(angle);
        float w = Mth.lerp(partialTick, widthO, width);
        float v = (tickCount+partialTick) * (precipitation == Biome.Precipitation.RAIN ? -.1F : -.01F);
        float[] out = new float[20];
        for (int i=0; i<4; i++) {
            boolean right = i==0 || i==3, bottom = i>=2;
            Vector3f point = new Vector3f(right ? w/2 : -w/2, bottom ? -length : 0, 0)
                .rotate(rotation).add(position);
            int o = i*5;
            out[o]=point.x; out[o+1]=point.y; out[o+2]=point.z;
            out[o+3]=.5F + (right ? -w/4 : w/4);
            out[o+4]=v + (bottom ? length/10 : 0);
        }
        return out;
    }
}

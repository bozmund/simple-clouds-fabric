package dev.nonamecrackers2.simpleclouds.client.renderer;

import java.util.List;

import org.joml.Matrix2f;
import org.joml.Matrix4f;
import org.joml.Vector2f;

import dev.nonamecrackers2.simpleclouds.client.renderer.v2.CloudsDrawPipeline;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBiomeTags;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;

/**
 * Atmospheric (high cirrus-type) clouds -- 26.3 adaptation of the 1.20.1
 * {@code AtmosphericCloudsRenderHandler}.
 *
 * <p>What it does: a purely visual 2D cloud layer drawn as a fullscreen pass
 * (see {@code core/atmospheric_clouds.fsh} + {@link CloudsDrawPipeline#drawAtmosphericClouds}).
 * Each screen fragment casts a ray up to a plane 5000 blocks above the camera and
 * samples psrdnoise there. The look of the layer depends on the biome the player
 * is in (cold/dry/savanna -> sparse cirrostratus; hot -> dense cirrocumulus;
 * plains/forest -> cirrus; elsewhere -> very sparse default), with a slow
 * cross-fade when the biome changes.
 *
 * <p>26.2 deviations from the original:
 * <ul>
 * <li>Full-screen pass on the main render target instead of a Forge post chain
 * (PostChain/EffectInstance don't exist in 26.2; the pass pattern used by the
 * storm fog / terrain shadow passes is the native equivalent).</li>
 * <li>Original Forge biome tags map to Fabric's conventional biome tags,
 * retaining original formation precedence, density and scale.</li>
 * <li>Original inverse projection/view ray reconstruction uses modern reversed-Z
 * near/far endpoints.</li>
 * </ul>
 */
public class AtmosphericCloudsRenderHandler
{
	private static final float SHIFT_MOVEMENT_SPEED = 0.005F;
	private static final float TRANSITION_SPEED = 0.001F;
	private static final int BIOME_CHECK_INTERVAL = 400;
	// (biome predicate, density, scaleX, scaleZ) -- in order of precedence.
	private static final Formation DEFAULT = new Formation(b -> b.value().getBaseTemperature() < 2.0F, 0.6F, 1.0F, 1.0F);
	private static final List<Formation> FORMATIONS = List.of(
		// Cirrostratus-like: cold / dry / savanna
		new Formation(b -> b.is(ConventionalBiomeTags.IS_COLD_OVERWORLD) || b.is(ConventionalBiomeTags.IS_DRY_OVERWORLD) || b.is(BiomeTags.IS_SAVANNA), 0.3F, 1.0F, 30.0F),
		// Cirrocumulus-like: hot
		new Formation(b -> b.is(ConventionalBiomeTags.IS_HOT_OVERWORLD), 0.8F, 10.0F, 10.0F),
		// Cirrus-like: plains / forest
		new Formation(b -> b.is(ConventionalBiomeTags.IS_PLAINS) || b.is(BiomeTags.IS_FOREST), 1.0F, 2.0F, 10.0F),
		DEFAULT);

	private final Minecraft mc;
	private Vector2f windDirection = new Vector2f(1.0F, 0.0F);
	private float shiftMovement;
	private float shiftMovementO;
	private float transition;
	private float transitionO;
	private int tickCount;
	private Formation formation = DEFAULT;
	private Formation nextFormation;

	public AtmosphericCloudsRenderHandler(Minecraft mc)
	{
		this.mc = mc;
	}

	public void setWindDirection(Vector2f direction)
	{
		this.windDirection = new Vector2f(direction);
	}

	public void tick()
	{
		this.tickCount++;

		this.shiftMovementO = this.shiftMovement;
		this.shiftMovement += SHIFT_MOVEMENT_SPEED;

		this.transitionO = this.transition;
		if (this.nextFormation != null)
		{
			this.transition += TRANSITION_SPEED;
			if (this.transition > 1.0F)
			{
				this.formation = this.nextFormation;
				this.nextFormation = null;
				this.transition = 0.0F;
				this.transitionO = 0.0F;
			}
		}

		if (this.mc.level != null && this.tickCount % BIOME_CHECK_INTERVAL == 0)
		{
			BlockPos pos = this.mc.gameRenderer.mainCamera().blockPosition();
			BiomeManager biomeManager = this.mc.level.getBiomeManager();
			if (biomeManager != null)
			{
				Holder<Biome> holder = biomeManager.getBiome(pos);
				for (Formation candidate : FORMATIONS)
				{
					if (candidate.rendersIn().test(holder))
					{
						if (this.formation != candidate && this.nextFormation != candidate && this.transition == 0.0F)
							this.nextFormation = candidate;
						break;
					}
				}
			}
		}
	}

	/**
	 * Draws the layer. Called by {@link SimpleCloudsRenderer} immediately after
	 * the sky, before terrain and voxel clouds, as in the original pipeline.
	 *
	 * @param viewMatrix world -> camera (rotation part used for the rays)
	 * @param partialTick for lerping the shift/transition animations
	 * @param cloudR cloud color (vanilla cloud brightness, 0..1)
	 * @param cloudG cloud color
	 * @param cloudB cloud color
	 * @param projection the active world projection, including camera effects
	 */
	public void render(CloudsDrawPipeline pipeline, Matrix4f viewMatrix, Matrix4f projection, float partialTick,
			float cloudR, float cloudG, float cloudB)
	{
		if (this.formation == null)
			return;

		float shift = Mth.lerp(partialTick, this.shiftMovementO, this.shiftMovement);
		float transition = Mth.lerp(partialTick, this.transitionO, this.transition);
		float yaw = (float) Mth.atan2(this.windDirection.x, this.windDirection.y);

		float alpha = 1.0F;
		Entity cameraEntity = this.mc.getCameraEntity();
		if (cameraEntity instanceof LivingEntity living)
		{
			var map = living.getActiveEffectsMap();
			if (map.containsKey(MobEffects.BLINDNESS))
			{
				MobEffectInstance instance = map.get(MobEffects.BLINDNESS);
				alpha = instance.isInfiniteDuration() ? 0.0F : 1.0F - Mth.clamp((float) instance.getDuration() / 20.0F, 0.0F, 1.0F);
			}
			else if (map.containsKey(MobEffects.DARKNESS))
			{
				// 26.2: getFactorData() is gone; the darkness vignette is strongest at
				// the start of the effect and fades out -- approximate with duration.
				MobEffectInstance instance = map.get(MobEffects.DARKNESS);
				float dur = instance.isInfiniteDuration() ? 1.0F : Mth.clamp((float) instance.getDuration() / 20.0F, 0.0F, 1.0F);
				alpha = 1.0F - Mth.clamp(dur, 0.2F, 1.0F);
			}
		}

		// Pass 1: the current formation (density scaled down while transitioning).
		pipeline.drawAtmosphericClouds(viewMatrix, projection, this.formation.transform(yaw), shift,
				1.0F - transition, this.formation.density(), cloudR, cloudG, cloudB, alpha);
		// Pass 2: the incoming formation while transitioning (full density at transition=1).
		if (transition > 0.0F)
		{
			Formation incoming = this.nextFormation != null ? this.nextFormation : DEFAULT;
			pipeline.drawAtmosphericClouds(viewMatrix, projection, incoming.transform(yaw), shift,
					transition, incoming.density(), cloudR, cloudG, cloudB, alpha);
		}
	}

	public void close()
	{
		this.formation = DEFAULT;
		this.nextFormation = null;
		this.transition = 0.0F;
		this.transitionO = 0.0F;
		this.tickCount = 0;
		this.shiftMovement = 0.0F;
		this.shiftMovementO = 0.0F;
		this.windDirection = new Vector2f(1.0F, 0.0F);
	}

	/** (biome predicate, density, scale in wind direction, scale crosswind). */
	public record Formation(java.util.function.Predicate<Holder<Biome>> rendersIn, float density, float scaleX, float scaleZ)
	{
		/** The shader's Transform matrix: scale then rotate by the wind yaw. */
		public Matrix2f transform(float yaw)
		{
			Matrix2f m = new Matrix2f().identity();
			m.scale(this.scaleX, this.scaleZ);
			m.rotateLocal(yaw);
			return m;
		}
	}
}

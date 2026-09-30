package dev.nonamecrackers2.simpleclouds.client.renderer;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig;

import dev.nonamecrackers2.simpleclouds.client.renderer.lightning.LightningBolt;
import dev.nonamecrackers2.simpleclouds.client.renderer.rain.PrecipitationQuad;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.AABB;
import net.minecraft.util.Mth;
import dev.nonamecrackers2.simpleclouds.common.cloud.CloudType;
import dev.nonamecrackers2.simpleclouds.common.cloud.SimpleCloudsConstants;
import dev.nonamecrackers2.simpleclouds.common.world.CloudManager;
import net.minecraft.sounds.SoundEvent;
import dev.nonamecrackers2.simpleclouds.common.init.SimpleCloudsSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.GraphicsPreset;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * World precipitation, lightning geometry and thunder for the 26.3 backend.
 * Precipitation uses the original wind/collision/animation equations. Remaining
 * weather/render parity work is tracked in plans/2026-09-20-PARITY-PROGRESS.md.
 */
public class WorldEffects
{
	private static final org.apache.logging.log4j.Logger LOGGER = 
			org.apache.logging.log4j.LogManager.getLogger("simpleclouds/WorldEffects");

	// Scan box constants mirror the 1.20.1 WorldEffects (RAIN_SCAN_WIDTH/2, etc.).
	private static final int SCAN_RADIUS = 16;
	private static final int RAIN_Y_MIN = 8; // RAIN_HEIGHT_OFFSET above the camera
	private static final int RAIN_Y_SPAN = 8; // RAIN_SCAN_HEIGHT

	// Verification flag: force rain + thunder regardless of the world's weather.
	// Verified working 2026-09-11 (rain streaks + storm scene, see PORTING.md);
	// keep false in production -- rain follows the vanilla world weather.
	private static final boolean DEBUG_FORCE_WEATHER = false;

	// 1.20.1 weighted lightning bolt colors (SimpleWeightedRandomList ported as
	// parallel arrays; same weights/order).
	private static final int[] LIGHTNING_COLORS = { 0xFFFFFFFF, 0xFF8C80FF, 0xFF8C80FF, 0xFFF0FFB4, 0xFFFFB4BE };
	private static final int[] LIGHTNING_WEIGHTS = { 30, 13, 12, 10, 5 };
	private static final int LIGHTNING_TOTAL_WEIGHT = 65;

	/** Live lightning bolts (server-packet spawned; ticked here, rendered by the pipeline). */
	private final java.util.List<LightningBolt> lightningBolts = new java.util.ArrayList<>();

	private final Minecraft mc;
	private final SimpleCloudsRenderer renderer;
	private final Random random = new Random();
	private final Map<Long, PrecipitationQuad> drops = new HashMap<>();
	private int rainDelay = 20;
	private @Nullable CloudType typeAtCamera;
	private float fadeAtCamera;
	private float storminessAtCamera;
	private float storminessSmoothed;
	private float storminessSmoothedO;

	public WorldEffects(Minecraft mc, SimpleCloudsRenderer renderer)
	{
		this.mc = mc;
		this.renderer = renderer;
	}

	public void renderPost(PoseStack stack, float partialTick, double camX, double camY, double camZ, float scale)
	{
		this.updateCameraWeather(camX, camY, camZ);
	}

	private void updateCameraWeather(double camX, double camY, double camZ)
	{
		if (this.mc.level == null) return;
		CloudManager<ClientLevel> manager = CloudManager.get(this.mc.level);
		if (manager == null) return;
		var result = manager.getCloudTypeAtWorldPos((float)camX, (float)camZ);
		this.typeAtCamera = result == null ? null : result.getLeft();
		this.fadeAtCamera = result == null ? 1.0F : result.getRight();
		this.storminessAtCamera = 0.0F;
		if (!manager.shouldUseVanillaWeather())
		{
			if (this.typeAtCamera != null && this.typeAtCamera.weatherType().causesDarkening())
			{
				float verticalFade = 1.0F - Mth.clamp(((float)camY -
					(this.typeAtCamera.stormStart() * SimpleCloudsConstants.CLOUD_SCALE + manager.getCloudHeight())) /
					SimpleCloudsConstants.RAIN_VERTICAL_FADE, 0.0F, 1.0F);
				float factor = Mth.clamp((1.0F - this.fadeAtCamera) * 3.0F, 0.0F, 1.0F);
				this.storminessAtCamera = this.typeAtCamera.storminess() * factor * verticalFade;
			}
			this.mc.level.setRainLevel(manager.getRainLevel((float)camX, (float)camY, (float)camZ));
		}
	}

	/** Draws the custom rain quads into the scene (called from the renderer's frame flow). */
	public void renderRain(Matrix4f viewMatrix, float partialTick, double camX, double camY, double camZ)
	{
		var rainPipeline = this.renderer.getRainPipeline();
		if (rainPipeline == null || this.drops.isEmpty())
			return;
		rainPipeline.captureBackdrop();

		for (Biome.Precipitation type : new Biome.Precipitation[] {Biome.Precipitation.RAIN, Biome.Precipitation.SNOW})
		{
			List<float[]> out = new ArrayList<>();
			for (PrecipitationQuad drop : this.drops.values())
				if (drop.getPrecipitation() == type)
					out.add(drop.vertices(partialTick, camX, camY, camZ));
			rainPipeline.setDrops(out, 1.0F);
			rainPipeline.draw(viewMatrix, PrecipitationQuad.TEXTURE_BY_PRECIPITATION.get(type));
		}
	}

	public boolean hasLightningToRender()
	{
		// Original: bolts exist. (The port used to gate this on the spawn-time
		// flash state, which made bolts invisible whenever the flash had lapsed.)
		return !this.lightningBolts.isEmpty();
	}

	public void forLightning(Consumer<LightningBolt> consumer)
	{
		this.lightningBolts.forEach(consumer);
	}

	/**
	 * 26.2 port of the 1.20.1 bolt render: world-space quads from every live bolt,
	 * one dynamic upload, additive "lightning" blend, depth-tested against terrain
	 * (no depth write), no fog (the original disabled fog for this pass).
	 */
	public void renderLightning(org.joml.Matrix4f viewMatrix, float partialTick, double camX, double camY, double camZ,
			dev.nonamecrackers2.simpleclouds.client.renderer.v2.CloudsDrawPipeline pipeline)
	{
		if (this.lightningBolts.isEmpty())
			return;
		float[] out = new float[4096 * 7];
		int written = 0;
		for (LightningBolt bolt : this.lightningBolts)
		{
			float dist = (float) Math.sqrt(
					(bolt.getPosition().x - camX) * (bolt.getPosition().x - camX)
							+ (bolt.getPosition().y - camY) * (bolt.getPosition().y - camY)
							+ (bolt.getPosition().z - camZ) * (bolt.getPosition().z - camZ));
			// Storm plan step 1: mirror the 1.20.1 original — while a rendered bolt
			// is within CLOSE_THUNDER_CUTOFF (2000 blocks) and still bright
			// (lifetime fade > 0.5), keep the 2-tick vanilla sky flash renewed, so
			// the flash lasts exactly as long as a nearby bolt is bright. Distant
			// strikes (even 12,000 blocks away) apply NO flash. Vanilla's lightmap
			// honors "Hide Sky Flashes"; the fog lift (flashStrength) is gated
			// separately on the same option.
			if (dist <= SimpleCloudsConstants.CLOSE_THUNDER_CUTOFF && bolt.getFade(partialTick) > 0.5F)
				this.mc.level.setSkyFlashTime(2);
			float fogStart = this.renderer.getFogStart();
			float fogEnd = this.renderer.getFogEnd();
			float alpha = net.minecraft.util.Mth.clamp(1.0F - (dist - fogStart) / (fogEnd - fogStart), 0.0F, 1.0F);
			int needed = Math.addExact(written, bolt.renderFloatCount(partialTick));
			if (needed > out.length)
				out = java.util.Arrays.copyOf(out, Math.max(needed, out.length * 2));
			written += bolt.renderInto(out, written, partialTick, 1.0F, 1.0F, 1.0F, alpha);
		}
		if (written <= 0)
			return;
		java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocateDirect(written * 4).order(java.nio.ByteOrder.nativeOrder());
		for (int i = 0; i < written; i++)
			buffer.putFloat(out[i]);
		buffer.flip();
		pipeline.drawLightning(viewMatrix, buffer, written / 7);
	}

	/**
	 * A lightning strike (from the server packet or the local cloud manager).
	 * Slice: starts a decaying flash + plays the thunder sound; no bolt mesh.
	 */
	public void spawnLightning(BlockPos pos, boolean onlySound, int seed, int depth, int branchCount, float maxBranchLength, float maxWidth, float minimumPitch, float maximumPitch)
	{
		ClientLevel level = this.mc.level;
		if (level == null || this.mc.player == null)
		{
			return;
		}
		// The original measures thunder and flash range from the camera, not the
		// player entity; they differ in third-person and detached-camera views.
		var cameraPos = this.mc.gameRenderer.mainCamera().position();
		double dx = cameraPos.x - (pos.getX() + 0.5);
		double dy = cameraPos.y - (pos.getY() + 0.5);
		double dz = cameraPos.z - (pos.getZ() + 0.5);
		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
		// Storm plan step 0/1: every strike (server-spawned, client-spawned or
		// DevShot-forced) logs its distance to the camera and the flash it is
		// ALLOWED to apply, so S5 has per-strike proof data.
		LOGGER.info("[DEVSHOT-LIGHTNING] strike at {}x{}x{}: distToCam={} blocks, onlySound={}, flash {}",
			pos.getX(), pos.getY(), pos.getZ(), Math.round(distance), onlySound,
			distance <= SimpleCloudsConstants.CLOSE_THUNDER_CUTOFF
					? "sky-flash (2t per frame while bolt bright, fade>0.5)"
					: "none (beyond the 2000-block flash cutoff)");
		// Storm plan step 2: the original's thunder. CLOSE_THUNDER within
		// CLOSE_THUNDER_CUTOFF (2000 blocks, also the attenuation cutoff),
		// otherwise DISTANT_THUNDER attenuated over the configured distance;
		// pitch 0.5+fade*0.5 (fade 1 at THUNDER_PITCH_FULL_DIST=3000, 0 from
		// THUNDER_PITCH_MINIMUM_DIST=5000 out), volume 1+rand*4, and a delay of
		// dist / (SOUND_METERS_PER_SECOND=2000 blocks/s) * 20 ticks so the rumble
		// arrives at the speed of sound. The port used to play an undelayed,
		// fixed-pitch local sound at the strike position.
		SoundEvent sound = SimpleCloudsSounds.DISTANT_THUNDER;
		int attenuation = SimpleCloudsConfig.CLIENT.thunderAttenuationDistance.get();
		float dist = (float) distance;
		if (dist < SimpleCloudsConstants.CLOSE_THUNDER_CUTOFF)
		{
			sound = SimpleCloudsSounds.CLOSE_THUNDER;
			attenuation = SimpleCloudsConstants.CLOSE_THUNDER_CUTOFF;
		}
		float fade = 1.0F - Math.min(Math.max(dist - (float) SimpleCloudsConstants.THUNDER_PITCH_FULL_DIST, 0.0F)
				/ ((float) SimpleCloudsConstants.THUNDER_PITCH_MINIMUM_DIST - (float) SimpleCloudsConstants.THUNDER_PITCH_FULL_DIST), 1.0F);
		// 1.20.1 parity: the bolt's seed also drives its shape — one RandomSource
		// draws the thunder volume first, then the bolt geometry (same order as
		// the original).
		net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create(seed & 0xFFFFFFFFL);
		float volume = 1.0F + random.nextFloat() * 4.0F;
		float pitch = 0.5F + fade * 0.5F;
		dev.nonamecrackers2.simpleclouds.client.sound.AdjustableAttenuationSoundInstance instance =
				new dev.nonamecrackers2.simpleclouds.client.sound.AdjustableAttenuationSoundInstance(
						sound, SoundSource.WEATHER, volume, pitch, random,
						pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, attenuation);
		int delayTicks = net.minecraft.util.Mth.floor(dist / SimpleCloudsConstants.SOUND_METERS_PER_SECOND) * 20;
		this.mc.getSoundManager().playDelayed(instance, delayTicks);
		LOGGER.info("[DEVSHOT-LIGHTNING] thunder: sound={}, delay={} ticks ({} s), pitch={}, volume={}, attenuation={}",
				sound, delayTicks, String.format(java.util.Locale.ROOT, "%.2f", delayTicks / 20.0),
				String.format(java.util.Locale.ROOT, "%.2f", pitch),
				String.format(java.util.Locale.ROOT, "%.2f", volume), attenuation);
		if (onlySound)
			return;

		float r = 1.0F, g = 1.0F, b = 1.0F;
		if (SimpleCloudsConfig.CLIENT.lightningColorVariation.get())
		{
			int roll = random.nextInt(LIGHTNING_TOTAL_WEIGHT);
			for (int i = 0; i < LIGHTNING_COLORS.length; i++)
			{
				roll -= LIGHTNING_WEIGHTS[i];
				if (roll < 0)
				{
					int color = LIGHTNING_COLORS[i];
					r = ((color >> 16) & 0xFF) / 255.0F;
					g = ((color >> 8) & 0xFF) / 255.0F;
					b = (color & 0xFF) / 255.0F;
					break;
				}
			}
		}
		this.lightningBolts.add(new LightningBolt(random,
				new org.joml.Vector3f(pos.getX() + 0.5F, pos.getY() + 0.5F, pos.getZ() + 0.5F),
				depth, branchCount, maxBranchLength, maxWidth, minimumPitch, maximumPitch, r, g, b));
	}

	public void modifyLightMapTexture(float partialTick, int pixelX, int pixelY, Vector3f color)
	{
		// Slice: no lightmap glow (26.2's LightTexture layout differs).
	}

	/** 0..1: how much storm-type cloud is above the camera (drives fog/sky tinting). */
	public float getStorminessAtCamera()
	{
		return this.storminessAtCamera;
	}

	/**
	 * 0..1 lightning flash strength at the given partial tick. Storm plan step 1:
	 * the port used to start a 1.2-2.75 s global flash on EVERY strike at any
	 * distance (even sound-only ones) — that is what made far storms flash the
	 * whole screen. Now the strength is driven by the VANILLA sky flash, which
	 * renderLightning renews (2 ticks) per frame only while a rendered bolt is
	 * within CLOSE_THUNDER_CUTOFF (2000 blocks) and still bright (lifetime fade
	 * > 0.5) — the original's mechanism — and is zero when the player enabled
	 * "Hide Sky Flashes" (vanilla's lightmap flash honors the option itself; the
	 * storm-fog lift through this channel must as well).
	 */
	public float flashStrength(float partialTick)
	{
		ClientLevel level = this.mc.level;
		if (level == null || this.mc.options == null)
			return 0.0F;
		if (this.mc.options.hideLightningFlash().get())
			return 0.0F;
		int remaining = ((dev.nonamecrackers2.simpleclouds.mixin.MixinClientLevelAccessor) level).simpleclouds$getSkyFlashTime();
		if (remaining <= 0)
			return 0.0F;
		float t = Math.min(remaining, 2) / 2.0F;
		// keep the original-style flicker so the lift reads as lightning
		return (float) (t * (0.7 + 0.3 * Math.abs(Math.sin(t * 9.0))));
	}

	/**
	 * Advances the rain drops and the flash. Runs on the client tick (see
	 * SimpleCloudsClientEvents). The drop scan mirrors the 1.20.1 original:
	 * per block position in the scan box a position-seeded hash decides
	 * occupancy (3%), drops live 60-119 ticks with a 20-tick width fade
	 * in/out, and length comes from a collision ray along the wind angle.
	 */
	public void tick()
	{
		if (this.rainDelay > 0)
			this.rainDelay--;
		// Advance + reap bolts regardless of the weather state.
		var lightning = this.lightningBolts.iterator();
		while (lightning.hasNext())
		{
			LightningBolt bolt = lightning.next();
			bolt.tick();
			if (bolt.isDead())
				lightning.remove();
		}
		ClientLevel level = this.mc.level;
		Player player = this.mc.player;
		if (level == null || player == null)
		{
			this.reset();
			return;
		}
		var camera = this.mc.gameRenderer.mainCamera().position();
		this.updateCameraWeather(camera.x, camera.y, camera.z);
		this.storminessSmoothedO = this.storminessSmoothed;
		this.storminessSmoothed += (this.storminessAtCamera - this.storminessSmoothed) / 25.0F;
		if (DEBUG_FORCE_WEATHER)
		{
			level.setRainLevel(1.0F);
			level.setThunderLevel(1.0F);
		}
		float rain = level.getRainLevel(1.0F);
		BlockPos camPos = this.mc.gameRenderer.mainCamera().blockPosition();
		int camX = camPos.getX(), camY = camPos.getY(), camZ = camPos.getZ();
		float xRot = SimpleCloudsConfig.CLIENT.rainAngle.get().floatValue() * ((float)Math.PI / 180.0F);
		var direction = CloudManager.get(level).calculateWindDirection();
		float yRot = (float)-Mth.atan2(direction.x, direction.y);
		float pitchCos = Mth.cos(xRot - (float)Math.PI / 2.0F);
		int xOffset = Mth.floor(Mth.sin(-yRot) * pitchCos * SCAN_RADIUS);
		int zOffset = Mth.floor(Mth.cos(-yRot) * pitchCos * SCAN_RADIUS);
		// Original 1.20.1 halves the precipitation scan radius on Fast graphics.
		// 26.3 exposes this through the graphics preset instead of useFancyGraphics().
		int radius = this.mc.options.graphicsPreset().get() == GraphicsPreset.FAST ? SCAN_RADIUS / 2 : SCAN_RADIUS;
		int minX = camX-radius-xOffset, maxX = camX+radius-xOffset;
		int minZ = camZ-radius-zOffset, maxZ = camZ+radius-zOffset;
		AABB box = new AABB(minX, camY+RAIN_Y_MIN, minZ, maxX, camY+RAIN_Y_MIN+RAIN_Y_SPAN, maxZ);

		// Age the surviving drops; drop the dead ones and the ones that left the
		// camera-following scan box.
		Iterator<Map.Entry<Long, PrecipitationQuad>> it = this.drops.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<Long, PrecipitationQuad> entry = it.next();
			PrecipitationQuad drop = entry.getValue();
			var pos = drop.getPos();
			// Match the original's center-of-block containment at scan boundaries.
			boolean inBox = box.contains(pos.x + 0.5D, pos.y + 0.5D, pos.z + 0.5D);
			if (drop.isDead() || !inBox)
				it.remove();
			else
				drop.tick();
		}

		// Spawn drops in the scan box (the original's per-position scan).
		if (rain <= 0.0F || this.rainDelay != 0 || !level.getBiome(camPos).value().hasPrecipitation())
			return;
		for (int x = minX; x < maxX; x++)
		{
			for (int z = minZ; z < maxZ; z++)
			{
				for (int y = camY + RAIN_Y_MIN; y < camY + RAIN_Y_MIN + RAIN_Y_SPAN; y++)
				{
					long key = BlockPos.asLong(x, y, z);
					if (this.drops.containsKey(key))
						continue;
					// Same deterministic occupancy as the original (position-seeded):
					// nextInt(100) <= 2, i.e. 3% of the columns. (nextLong() % 100 is
					// negative for about half the seeds, so it used to pass ~51%.)
					if (RandomSource.create(key).nextInt(100) > 2)
						continue;
					int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
					if (ground > y)
						continue;
					BlockPos pos = new BlockPos(x, y, z);
					Biome.Precipitation type = level.getBiome(pos).value().getPrecipitationAt(pos, level.getSeaLevel());
					if (type == Biome.Precipitation.NONE)
						continue;
					PrecipitationQuad drop = new PrecipitationQuad(type, level::clip, pos,
						xRot + this.random.nextFloat()*.1F, yRot + this.random.nextFloat()*.1F,
						60 + this.random.nextInt(60), rain * (type == Biome.Precipitation.SNOW ? 4.0F : 2.0F));
					drop.tick(); // Original also advances newly spawned quads this tick.
					this.drops.put(key, drop);
				}
			}
		}
	}

	public Color calculateFogColor(float defaultR, float defaultG, float defaultB, float partialTick)
	{
		return hsbLerp(defaultR, defaultG, defaultB, 0.68F, 0.2F, -0.05F, this.getDarkenFactor(partialTick));
	}

	public Color calculateSkyColor(float defaultR, float defaultG, float defaultB, float partialTick)
	{
		return hsbLerp(defaultR, defaultG, defaultB, 0.63F, 0.1F, 0.05F, this.getDarkenFactor(partialTick));
	}

	private static Color hsbLerp(float r, float g, float b, float targetHue, float targetSaturation, float targetBrightness, float lerp)
	{
		float[] hsb = Color.RGBtoHSB((int)(r * 255.0F), (int)(g * 255.0F), (int)(b * 255.0F), null);
		if (targetHue < hsb[0]) targetHue += 1.0F;
		float hue = Mth.lerp(lerp, targetHue, hsb[0]);
		float sat = Mth.clamp(Mth.lerp(lerp, targetSaturation, hsb[1]), 0.0F, 1.0F);
		float bright = Mth.clamp(Mth.lerp(lerp, targetBrightness, hsb[2]), 0.0F, 1.0F);
		return Color.getHSBColor(hue, sat, bright);
	}

	public void reset()
	{
		this.drops.clear();
		this.rainDelay = 20;
		this.typeAtCamera = null;
		this.fadeAtCamera = 1.0F;
		this.storminessAtCamera = this.storminessSmoothed = this.storminessSmoothedO = 0.0F;
		this.lightningBolts.clear();
	}

	public @Nullable CloudType getCloudTypeAtCamera()
	{
		return this.typeAtCamera;
	}

	public float getFadeRegionAtCamera()
	{
		return this.fadeAtCamera;
	}

	public float getStorminessSmoothed(float partialTick)
	{
		return Mth.lerp(partialTick, this.storminessSmoothedO, this.storminessSmoothed);
	}

	public float getDarkenFactor(float partialTick, float strength)
	{
		return Mth.clamp(1.0F - this.getStorminessSmoothed(partialTick) * strength, 0.1F, 1.0F);
	}

	public float getDarkenFactor(float partialTick)
	{
		return this.getDarkenFactor(partialTick, 1.2F);
	}

	public List<LightningBolt> getLightningBolts()
	{
		return List.copyOf(this.lightningBolts);
	}
}

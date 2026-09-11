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

import dev.nonamecrackers2.simpleclouds.client.renderer.lightning.LightningBolt;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.WorldEffectsDrop;
import dev.nonamecrackers2.simpleclouds.common.cloud.CloudType;
import dev.nonamecrackers2.simpleclouds.common.init.SimpleCloudsSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 26.2 vertical-slice world effects.
 *
 * Ported for real: custom rain (per-drop camera-facing quads around the camera,
 * deterministic 2% occupancy per block column like the 1.20.1 PrecipitationQuad
 * scan, heightmap-measured drop length, scrolling texture = falling motion) and
 * lightning FLASHES (spawnLightning triggers a decaying flash that drives the
 * storm-fog LightningMul hook, plus the mod's thunder sounds).
 *
 * Slice deviations (documented):
 * - No lightning bolt MESH (the 1.20.1 jagged branch geometry + lightmap glow
 *   are not ported; the flash + sound carry the effect).
 * - No wind tilt on rain (the original tilted drops with the weather direction;
 *   this slice drops straight down).
 * - 26.2's Level API exposes no snow level, so only rain.png is used.
 * - Sky/fog coloring: the fog gets a small storm tint; the original's per-biome
 *   storm sky tint is not ported.
 */
public class WorldEffects
{
	// Scan box constants mirror the 1.20.1 WorldEffects (RAIN_SCAN_WIDTH/2, etc.).
	private static final int SCAN_RADIUS = 16;
	private static final int RAIN_Y_MIN = 8; // RAIN_HEIGHT_OFFSET above the camera
	private static final int RAIN_Y_SPAN = 8; // RAIN_SCAN_HEIGHT
	private static final float MAX_LENGTH = 32.0F;
	private static final float UV_SCROLL_PER_TICK = 0.1F; // original rain texture scroll

	// Verification flag: force rain + thunder regardless of the world's weather.
	// Verified working 2026-09-11 (rain streaks + storm scene, see PORTING.md);
	// keep false in production -- rain follows the vanilla world weather.
	private static final boolean DEBUG_FORCE_WEATHER = false;

	/** One rain drop (the 1.20.1 PrecipitationQuad's mutable state). */
	private static final class Drop
	{
		final int x;
		final int y;
		final int z;
		final float baseWidth;
		final int life;
		float length;
		float uvOffset;
		int tick;

		Drop(int x, int y, int z, float length, float baseWidth, int life)
		{
			this.x = x;
			this.y = y;
			this.z = z;
			this.length = length;
			this.baseWidth = baseWidth;
			this.life = life;
		}

		boolean isDead()
		{
			return this.tick > this.life;
		}

		void age()
		{
			this.tick++;
			this.uvOffset -= UV_SCROLL_PER_TICK;
		}

		/** 20-tick fade in/out ramp (as in PrecipitationQuad.tick). */
		float rampedWidth()
		{
			float in = Math.min(1.0F, this.tick / 20.0F);
			float out = Math.min(1.0F, (this.life - this.tick) / 20.0F);
			return Math.max(0.1F, this.baseWidth * Math.min(in, out));
		}
	}

	private final Minecraft mc;
	private final SimpleCloudsRenderer renderer;
	private final Random random = new Random();
	private final Map<Long, Drop> drops = new HashMap<>();
	private int flashTicks;
	private int flashTotal;

	public WorldEffects(Minecraft mc, SimpleCloudsRenderer renderer)
	{
		this.mc = mc;
		this.renderer = renderer;
	}

	public void renderPost(PoseStack stack, float partialTick, double camX, double camY, double camZ, float scale)
	{
	}

	/** Draws the custom rain quads into the scene (called from the renderer's frame flow). */
	public void renderRain(Matrix4f viewMatrix, float partialTick, double camX, double camY, double camZ)
	{
		var rainPipeline = this.renderer.getRainPipeline();
		if (rainPipeline == null || this.drops.isEmpty())
			return;

		List<WorldEffectsDrop> out = new ArrayList<>(this.drops.size());
		for (Drop drop : this.drops.values())
		{
			out.add(new WorldEffectsDrop(
					drop.x + 0.5F, drop.y + 0.5F, drop.z + 0.5F,
					drop.length, drop.rampedWidth(),
					drop.uvOffset - partialTick * UV_SCROLL_PER_TICK));
		}
		rainPipeline.setDrops(out, 1.0F);
		rainPipeline.draw(viewMatrix);
	}

	public boolean hasLightningToRender()
	{
		return this.flashTicks > 0;
	}

	public void forLightning(Consumer<LightningBolt> consumer)
	{
		// Slice: no bolt meshes.
	}

	public void renderLightning(float partialTick, double camX, double camY, double camZ)
	{
		// Slice: the flash is applied through the storm fog's LightningMul.
	}

	/**
	 * A lightning strike (from the server packet or the local cloud manager).
	 * Slice: starts a decaying flash + plays the thunder sound; no bolt mesh.
	 */
	public void spawnLightning(BlockPos pos, boolean onlySound, int seed, int depth, int branchCount, float maxBranchLength, float maxWidth, float minimumPitch, float maximumPitch)
	{
		this.flashTotal = 24 + (seed & 31);
		this.flashTicks = this.flashTotal;

		ClientLevel level = this.mc.level;
		Player player = this.mc.player;
		if (onlySound || level == null || player == null)
			return;
		double dx = player.getX() - (pos.getX() + 0.5);
		double dy = player.getY() - (pos.getY() + 0.5);
		double dz = player.getZ() - (pos.getZ() + 0.5);
		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
		var sound = distance < 16.0 ? SimpleCloudsSounds.CLOSE_THUNDER : SimpleCloudsSounds.DISTANT_THUNDER;
		// playLocalSound handles the distance attenuation for the local player.
		level.playLocalSound(pos, sound, SoundSource.WEATHER, 1.0F, 1.0F, false);
	}

	public void modifyLightMapTexture(float partialTick, int pixelX, int pixelY, Vector3f color)
	{
		// Slice: no lightmap glow (26.2's LightTexture layout differs).
	}

	/** 0..1: how much storm-type cloud is above the camera (drives fog/sky tinting). */
	public float getStorminessAtCamera()
	{
		return this.renderer.getStormCoverage();
	}

	/** 0..1 lightning flash strength at the given partial tick (flickering decay). */
	public float flashStrength(float partialTick)
	{
		if (this.flashTicks <= 0)
			return 0.0F;
		float t = 1.0F - (this.flashTicks - partialTick) / (float) this.flashTotal;
		if (t <= 0.0F)
			return 1.0F;
		if (t >= 1.0F)
			return 0.0F;
		float envelope = t < 0.15F ? t / 0.15F : 1.0F - (t - 0.15F) / 0.85F;
		return (float) (Math.max(0.0, envelope) * (0.7 + 0.3 * Math.abs(Math.sin(t * 9.0))));
	}

	/**
	 * Advances the rain drops and the flash. Runs on the client tick (see
	 * SimpleCloudsClientEvents). The drop scan mirrors the 1.20.1 original:
	 * per block position in the scan box a position-seeded hash decides
	 * occupancy (2%), drops live 60-120 ticks with a 20-tick width fade
	 * in/out, and the length is the heightmap distance straight down.
	 */
	public void tick()
	{
		ClientLevel level = this.mc.level;
		Player player = this.mc.player;
		if (level == null || player == null)
		{
			this.drops.clear();
			return;
		}
		if (DEBUG_FORCE_WEATHER)
		{
			level.setRainLevel(1.0F);
			level.setThunderLevel(1.0F);
		}
		if (this.flashTicks > 0)
			this.flashTicks--;

		float rain = level.getRainLevel(0.0F);
		if (rain <= 0.02F)
		{
			this.drops.clear();
			return;
		}

		int camX = (int) Math.floor(player.getX());
		int camY = (int) Math.floor(player.getY());
		int camZ = (int) Math.floor(player.getZ());

		// Age the surviving drops; drop the dead ones and the ones that left the
		// camera-following scan box.
		Iterator<Map.Entry<Long, Drop>> it = this.drops.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<Long, Drop> entry = it.next();
			Drop drop = entry.getValue();
			boolean inBox = Math.abs(drop.x - camX) <= SCAN_RADIUS
					&& drop.y >= camY + RAIN_Y_MIN - 4 && drop.y < camY + RAIN_Y_MIN + RAIN_Y_SPAN + 4
					&& Math.abs(drop.z - camZ) <= SCAN_RADIUS;
			drop.age();
			if (drop.isDead() || !inBox)
				it.remove();
		}

		// Spawn drops in the scan box (the original's per-position scan).
		for (int x = camX - SCAN_RADIUS; x <= camX + SCAN_RADIUS; x++)
		{
			for (int z = camZ - SCAN_RADIUS; z <= camZ + SCAN_RADIUS; z++)
			{
				for (int y = camY + RAIN_Y_MIN; y < camY + RAIN_Y_MIN + RAIN_Y_SPAN; y++)
				{
					long key = BlockPos.asLong(x, y, z);
					if (this.drops.containsKey(key))
						continue;
					// Same 2% deterministic occupancy as the original (position-seeded).
					if ((int) (RandomSource.create(key).nextLong() % 100) > 2)
						continue;
					int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
					float length = (float) Math.min(MAX_LENGTH, Math.max(1.0, y - ground));
					this.drops.put(key, new Drop(x, y, z, length, rain * 2.0F, 60 + this.random.nextInt(60)));
				}
			}
		}
	}

	public Color calculateFogColor(float defaultR, float defaultG, float defaultB, float partialTick)
	{
		// Slice: nudge the fog toward the storm fog color with storminess.
		float s = this.getStorminessSmoothed(partialTick);
		return new Color(
				Math.round((defaultR + (0.04F - defaultR) * s * 0.5F) * 255.0F),
				Math.round((defaultG + (0.045F - defaultG) * s * 0.5F) * 255.0F),
				Math.round((defaultB + (0.06F - defaultB) * s * 0.5F) * 255.0F));
	}

	public Color calculateSkyColor(float defaultR, float defaultG, float defaultB, float partialTick)
	{
		return new Color(Math.round(defaultR * 255.0F), Math.round(defaultG * 255.0F), Math.round(defaultB * 255.0F));
	}

	public void reset()
	{
		this.drops.clear();
		this.flashTicks = 0;
	}

	public @Nullable CloudType getCloudTypeAtCamera()
	{
		return null;
	}

	public float getFadeRegionAtCamera()
	{
		return 1.0F;
	}

	public float getStorminessSmoothed(float partialTick)
	{
		return this.getStorminessAtCamera();
	}

	public float getDarkenFactor(float partialTick, float strength)
	{
		return 1.0F - this.flashStrength(partialTick) * 0.9F * strength;
	}

	public float getDarkenFactor(float partialTick)
	{
		return this.getDarkenFactor(partialTick, 1.0F);
	}

	public List<LightningBolt> getLightningBolts()
	{
		return List.of();
	}
}

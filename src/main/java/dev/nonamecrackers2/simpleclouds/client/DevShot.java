package dev.nonamecrackers2.simpleclouds.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import dev.nonamecrackers2.simpleclouds.client.cloud.ClientSideCloudTypeManager;
import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.CpuCloudGenerator;
import dev.nonamecrackers2.simpleclouds.client.world.ClientCloudManager;
import dev.nonamecrackers2.simpleclouds.api.common.cloud.weather.WeatherType;
import dev.nonamecrackers2.simpleclouds.common.cloud.CloudType;
import dev.nonamecrackers2.simpleclouds.common.cloud.region.CloudRegion;
import dev.nonamecrackers2.simpleclouds.common.cloud.spawning.CloudGenerator;
import dev.nonamecrackers2.simpleclouds.common.world.CloudManager;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.clock.ClockTimeMarkers;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.phys.Vec2;

/**
 * Dev-only deterministic screenshot for the automated test loop (dev-relaunch.sh).
 * When {@code <gameDir>/devshot.request} exists (it holds a frame count), the camera is
 * pointed straight up once a world is loaded and, after that many rendered frames,
 * the level is captured to {@code screenshots/devshot.png} straight from the render
 * target, so no other window can cover it and the HUD is not in it. The request file
 * is deleted afterwards. Without the file this costs one existence check.
 */
public final class DevShot
{
	private static final Logger LOGGER = LogManager.getLogger("simpleclouds/DevShot");
	private static boolean checked;
	private static boolean done;
	private static int framesLeft;
	private static float savedXRot;
	private static float savedYRot;
	private static boolean saved;
	private static boolean testSpawned;
	private static float shotAngle = -90.0F; // straight up by default
	private static float shotYaw = Float.NaN; // NaN = keep current yaw
	private static boolean shadowTest; // SHADOWTEST token: deterministic cloud-over-terrain scene

	private DevShot() {}

	/** Forces the overworld WorldClock to NOON (the save's time can be night after SIGKILL exits). */
	private static void forceNoon(Minecraft mc)
	{
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null)
		{
			LOGGER.info("[DEVSHOT] no integrated server (not singleplayer?); skipping clock override");
			return;
		}
		ServerClockManager clock = server.clockManager();
		if (clock == null)
		{
			LOGGER.info("[DEVSHOT] no ServerClockManager in the overworld data storage; skipping clock override");
			return;
		}
		try
		{
			net.minecraft.core.Holder<net.minecraft.world.clock.WorldClock> holder =
					server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(WorldClocks.OVERWORLD);
			if (!clock.moveToTimeMarker(holder, ClockTimeMarkers.NOON))
				clock.setTotalTicks(holder, 6000L);
			LOGGER.info("[DEVSHOT] moved the overworld clock to NOON for the screenshot");
		}
		catch (Throwable t)
		{
			LOGGER.warn("[DEVSHOT] clock override failed", t);
		}
	}

	private static void spawnTestFormation(Minecraft mc)
	{
		forceNoon(mc);
		try
		{
			ClientCloudManager manager = (ClientCloudManager) CloudManager.get(mc.level);
			if (manager == null)
			{
				LOGGER.warn("[DEVSHOT] no client cloud manager; skipping test formation");
				return;
			}
			CloudType[] types = ClientSideCloudTypeManager.getInstance().getIndexedCloudTypes();
			if (types == null || types.length == 0)
			{
				LOGGER.warn("[DEVSHOT] no cloud types loaded; skipping test formation");
				return;
			}
			// The world-fixed noise field is dense in some places and empty in others,
			// so spawning blindly at the player can land in a noise hole. Probe the
			// field around the player with the real CPU generator and spawn the test
			// formations in the densest cells (positions in cloud units, 1 = 8 blocks).
			float px = (float) (mc.player.getX() / 8.0);
			float pz = (float) (mc.player.getZ() / 8.0);
			LOGGER.info("[DEVSHOT] player at blocks {}x{}x{}, camera at {}x{}x{}",
				mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ(),
				mc.gameRenderer.mainCamera().position().x, mc.gameRenderer.mainCamera().position().y,
				mc.gameRenderer.mainCamera().position().z);
			float[] candX = new float[] { 0.0F, 10.0F, -10.0F, 20.0F, -20.0F, 14.0F, -14.0F, 26.0F };
			float[] candZ = new float[] { 0.0F, 10.0F, -10.0F, -20.0F, 20.0F, 14.0F, -14.0F, -26.0F };
			List<CpuCloudGenerator.CloudLayerGroup> groups = SimpleCloudsRenderer.dataDrivenGroups();
			Map<net.minecraft.resources.Identifier, Integer> typeToGroup = SimpleCloudsRenderer.dataDrivenGroupIndices();
			int best = 0;
			int bestCount = 0; // 0 (empty noise field) is never acceptable
			CloudType bestType = null;
			// Prefer a NON-STORM type: the camera ends up inside the formation, and
			// a storm type's storm fog would gray out the whole frame (it works, but
			// drowns out everything else the screenshot is trying to verify).
			for (int pass = 0; pass < 2 && bestType == null; pass++)
			{
				for (CloudType t : types)
				{
					if (t.id().toString().endsWith("empty"))
						continue;
					boolean storm = t.weatherType() != null && t.weatherType() != WeatherType.NONE;
					if ((pass == 0) == storm)
						continue; // pass 0: non-storm only; pass 1: anything
					for (int ci = 0; ci < candX.length; ci++)
					{
						int count = probeDensity(groups, typeToGroup, t, px + candX[ci], pz + candZ[ci]);
						if (count > bestCount)
						{
							bestCount = count;
							best = ci;
							bestType = t;
						}
					}
				}
			}
			if (bestType == null)
			{
				LOGGER.warn("[DEVSHOT] no renderable cloud type found");
				return;
			}
			// A ground-level bank (stratus: layers 0..32 cloud units = world Y 0..256)
			// at the player's position is the deterministic occlusion test: everything
			// below the terrain surface MUST be hidden by the terrain (depth test).
			// With the old null depth state it drew as white boxes through the ground.
			for (CloudType t : types)
			{
				// Plain stratus only (nimbostratus is a storm type and would gray the frame out).
				String name = t.id().toString();
				if (!name.endsWith("stratus") || name.endsWith("nimbostratus"))
					continue;
				for (int ci = 0; ci < candX.length; ci++)
				{
					int count = probeDensity(groups, typeToGroup, t, px + candX[ci], pz + candZ[ci]);
					if (count > 0)
					{
						CloudRegion r = new CloudRegion(t.id(), new Vec2(0.001F, 0.0F), 0.0F, 0.0F,
							px + candX[ci], pz + candZ[ci], 200.0F, 0.0F, 1.2F, 24000, 10, 9);
						manager.getCloudGenerator().addCloud(r, CloudGenerator.Order.USE_WEIGHT);
						LOGGER.info("[DEVSHOT] spawned ground-bank {} at cloud units {}x{} (probe density {})",
							t.id(), px + candX[ci], pz + candZ[ci], count);
						break;
					}
				}
				break;
			}
			for (int spawned = 0; spawned < 3; spawned++)
			{
				CloudType t = bestType;
				CloudRegion region = new CloudRegion(t.id(), new Vec2(0.001F, 0.0F), 0.0F, 0.0F,
					px + candX[best], pz + candZ[best], 200.0F, 0.0F, 1.2F, 24000, 10, spawned);
				if (manager.getCloudGenerator().addCloud(region, CloudGenerator.Order.USE_WEIGHT))
					LOGGER.info("[DEVSHOT] spawned test formation {} (r=20u) at cloud units {}x{} (probe density {})",
						t.id(), px + candX[best], pz + candZ[best], bestCount);
				spawned++;
			}
		}
		catch (Throwable t)
		{
			LOGGER.warn("[DEVSHOT] test formation spawn failed", t);
		}
	}

	/**
	 * Deterministic shadow scene: the player stands at the world spawn (same for
	 * every run) and a cumulus formation (world Y 144..400) is placed directly
	 * overhead at an absolute position, so the terrain in view has cloud above it
	 * while the horizon beyond the 160-block radius has open sky. A/B: the
	 * NOSHADOW token disables the terrain-shadow pass for comparison.
	 */
	private static void setupShadowTest(Minecraft mc)
	{
		forceNoon(mc);
		CloudManager<?> manager = CloudManager.get(mc.level);
		if (manager == null)
		{
			LOGGER.warn("[DEVSHOT] shadow test: no cloud manager");
			return;
		}
		// The world's own formations sit at spawn (dense, foggy, and stormy), so
		// relocate to the densest NON-STORM cell the noise probe finds: the only
		// clouds in view are then the single formation placed overhead of the
		// player, and no storm fog grays the frame.
		Map<net.minecraft.resources.Identifier, Integer> typeToGroup = SimpleCloudsRenderer.dataDrivenGroupIndices();
		List<CpuCloudGenerator.CloudLayerGroup> groups = SimpleCloudsRenderer.dataDrivenGroups();
		CloudType[] types = ClientSideCloudTypeManager.getInstance().getIndexedCloudTypes();
		float px = (float) (mc.player.getX() / 8.0);
		float pz = (float) (mc.player.getZ() / 8.0);
		// 2D grid probe (96-unit spacing, +/-288 units ~ 2300 blocks) x EVERY
		// non-storm type: the noise fields are patchy, the dense pocket may sit far
		// from spawn, and a type's layers may sit outside the probe's Y window.
		CloudType bestType = null;
		int bestCount = 0;
		float bestX = px, bestZ = pz;
		for (CloudType t : types)
		{
			if (t.weatherType() != null && t.weatherType() != WeatherType.NONE)
				continue; // storm types gray the frame out
			for (int i = -3; i <= 3; i++)
			{
				for (int j = -3; j <= 3; j++)
				{
					float qx = px + i * 96.0F;
					float qz = pz + j * 96.0F;
					int count = probeDensity(groups, typeToGroup, t, qx, qz);
					if (count > bestCount)
					{
						bestCount = count;
						bestType = t;
						bestX = qx;
						bestZ = qz;
					}
				}
			}
		}
		if (bestType == null)
		{
			LOGGER.warn("[DEVSHOT] shadow test: no dense non-storm cell found");
			return;
		}
		float cx = bestX;
		float cz = bestZ;
		int ground = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
				(int) (cx * 8.0), (int) (cz * 8.0));
		double py = Math.max(70.0, ground + 10.0);
		mc.player.teleportSetPosition(new net.minecraft.world.entity.PositionMoveRotation(
				new net.minecraft.world.phys.Vec3(cx * 8.0, py, cz * 8.0), net.minecraft.world.phys.Vec3.ZERO,
				mc.player.getYRot(), mc.player.getXRot()), java.util.EnumSet.noneOf(net.minecraft.world.entity.Relative.class));
		// Cumulus layers (16..48u) with posY 2u -> world Y 144..400, well above the player.
		// Radius 200u = 1600 blocks: with REGION_EDGE_FADE_FACTOR 0.005 a small radius
		// fades the whole formation (up to -5 noise). 200u keeps the player's area at
		// the center where the fade is zero.
		manager.getCloudGenerator().addCloud(new CloudRegion(bestType.id(), new Vec2(0.001F, 0.0F), 0.0F, 0.0F,
				cx, cz, 200.0F, 0.0F, 1.0F, 60_000, 60_000, 1), CloudGenerator.Order.USE_WEIGHT);
		LOGGER.info("[DEVSHOT] shadow test: {} (density {}) at {}x{}, player at Y {}", bestType.id(), bestCount, cx, cz, py);
	}

		/**
	 * Runs one small CPU generation (16x64x16 cells around the candidate center, masked
	 * by a single test formation of the given type) and returns the opaque instance
	 * count — i.e. how dense the world-fixed noise field is there for that type.
	 */
	private static int probeDensity(List<CpuCloudGenerator.CloudLayerGroup> allGroups,
			Map<net.minecraft.resources.Identifier, Integer> typeToGroup, CloudType type, float cx, float cz)
	{
		Integer group = typeToGroup.get(type.id());
		if (group == null)
			return -1;
		try
		{
			CpuCloudGenerator gen = new CpuCloudGenerator(allGroups);
			// Radius 2000 so the 16-unit probe window sits at the CENTER of the mask:
			// REGION_EDGE_FADE_FACTOR is 0.005, so a small radius puts the whole window
			// in the edge falloff (up to -5 noise) and everything is culled.
			gen.setRegions(List.of(new CpuCloudGenerator.RegionMask(cx, cz, 2000.0F, 1.0F, 0.0F, 0.0F, 1.0F, group)));
			float[] oc = new float[1];
			float[] tc = new float[1];
			float[] sc = new float[1];
			int x0 = Mth.floor(cx) - 8;
			int z0 = Mth.floor(cz) - 8;
			gen.generate(x0, 0, z0, x0 + 16, 64, z0 + 16, 8.0F, 0.0F, 0.0F, 0.0F, 0.0F, oc, tc, 0, sc);
			return (int) oc[0];
		}
		catch (Throwable t)
		{
			return -1;
		}
	}

	/** Called once per rendered world frame, after the clouds were drawn. */
	public static void onWorldFrame()
	{
		if (done)
			return;
		Minecraft mc = Minecraft.getInstance();
		Path request = mc.gameDirectory.toPath().resolve("devshot.request");
		if (!checked)
		{
			checked = true;
			if (!Files.exists(request))
			{
				done = true;
				return;
			}
			try
			{
				String content = Files.readString(request).trim();
				String[] parts = content.split("\\s+");
				// First token = frame count. The rest: numeric tokens are the camera
				// xRot (first) and yaw (second); keywords switch test scenes.
				framesLeft = Integer.parseInt(parts[0]);
				int numericSeen = 0;
				for (int i2 = 1; i2 < parts.length; i2++)
				{
					String part = parts[i2];
					if (part.equalsIgnoreCase("NOSHADOW"))
					{
						dev.nonamecrackers2.simpleclouds.client.renderer.v2.CloudsDrawPipeline.TERRAIN_SHADOWS_ENABLED = false;
						continue;
					}
					if (part.equalsIgnoreCase("SHADOWTEST"))
					{
						shadowTest = true;
						if (Float.isNaN(shotAngle))
							shotAngle = -45.0F; // default: 45 deg up (clouds overhead)
						continue;
					}
					try
					{
						float v = Float.parseFloat(part);
						if (numericSeen++ == 0)
							shotAngle = v;
						else
							shotYaw = v;
					}
					catch (NumberFormatException ignored) { /* unknown token */ }
				}
			}
			catch (Exception e) { framesLeft = 240; }
			LOGGER.info("[DEVSHOT] requested: capturing after {} frames", framesLeft);
		}
		if (mc.player == null)
			return;
		// Verification helper (automated loop only, i.e. devshot.request existed): the
		// world's formations may have drifted far outside the render band, so make sure
		// one exists above the player for the screenshot to verify cloud rendering.
		if (!testSpawned && framesLeft <= 230) // spawn early: band regen needs ~40 frames
		{
			testSpawned = true;
			if (shadowTest)
				setupShadowTest(mc);
			else
				spawnTestFormation(mc);
		}
		if (!saved)
		{
			savedXRot = mc.player.getXRot();
			savedYRot = mc.player.getYRot();
			saved = true;
		}
		mc.player.setXRot(shotAngle);
		if (!Float.isNaN(shotYaw))
			mc.player.setYRot(shotYaw);
		if (--framesLeft > 0)
			return;
		mc.player.setXRot(savedXRot); // restore the user's view
		mc.player.setYRot(savedYRot);
		done = true;
		Screenshot.grab(mc.gameDirectory, "devshot.png", mc.gameRenderer.mainRenderTarget(), 1,
				message -> LOGGER.info("[DEVSHOT] saved screenshots/devshot.png ({})", message.getString()));
		try { Files.deleteIfExists(request); }
		catch (Exception ignored) {}
	}
}

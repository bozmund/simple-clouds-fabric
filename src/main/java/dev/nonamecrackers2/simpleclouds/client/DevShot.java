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
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.clock.ClockTimeMarkers;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.phys.Vec2;

/**
 * Dev-only deterministic screenshots for the automated test loop (dev-relaunch.sh).
 * When {@code <gameDir>/devshot.request} exists, the camera is controlled and the
 * level is captured straight from the render target (no HUD, no window stacking).
 *
 * Request format: {@code FRAMES [xRot] [yaw] [TOKEN ...]}
 *
 * Legacy tokens (single shot to {@code screenshots/devshot.png}):
 * <ul>
 * <li>two numeric tokens: camera xRot (default -90, straight up) and yaw</li>
 * <li>{@code NOSHADOW} skip the terrain shadow pass, {@code SHADOWTEST} deterministic
 * cloud-over-terrain scene, {@code BOLT} deterministic lightning, {@code OPTIONS} open
 * the options screen after the shot.</li>
 * </ul>
 *
 * Standard views (VISUAL-PARITY-PLAN step 0) — each letter queues one shot, taken in
 * order after a settle delay; positions are probed from the live terrain so the same
 * world always yields the same views:
 * <ul>
 * <li>{@code A} horizon: on a beach at sea level (y~63-67), pitch 0, facing open water
 * &rarr; {@code devshot-A.png}</li>
 * <li>{@code B} landscape: over land, held at y=100, pitch -15 &rarr; {@code devshot-B.png}</li>
 * <li>{@code C} up: straight up from the beach (same position as A) &rarr; {@code devshot-C.png}</li>
 * <li>{@code D} inside/above: the beach XZ held at y=260, pitch -20 &rarr; {@code devshot-D.png}</li>
 * <li>{@code E} motion: view A twice, 200 game ticks (10 s) apart &rarr;
 * {@code devshot-E1.png} / {@code devshot-E2.png}</li>
 * <li>{@code NOSPAWN} skip the automatic test-formation spawn (shows the world's own
 * persisted formations only).</li>
 * </ul>
 * Held (pinned) views teleport the player to the view position every frame and switch
 * it to creative flying, so the camera stays exactly where the view says.
 */
public final class DevShot
{
	private static final Logger LOGGER = LogManager.getLogger("simpleclouds/DevShot");
	private static boolean checked;
	private static boolean done;
	private static int framesLeft;
	private static int framesTotal;
	private static float savedXRot;
	private static float savedYRot;
	private static boolean saved;
	private static boolean testSpawned;
	private static float shotAngle = -90.0F; // straight up by default
	private static float shotYaw = Float.NaN; // NaN = keep current yaw
	private static boolean shadowTest; // SHADOWTEST token: deterministic cloud-over-terrain scene
	private static boolean boltTest; // BOLT token: deterministic lightning bolt for the screenshot

	// ---- standard views (A-E) ----

	/** One queued camera view: where to stand, where to look, what to capture. */
	private static final class View
	{
		String file1;
		String file2; // second shot of this view (motion), null otherwise
		float pitch;
		float yaw;
		double x, y, z;
		boolean pin; // teleport the player to (x,y,z) every frame
		long waitTicks; // after file1: wait this many game ticks, then take file2

		View(String file1, float pitch, float yaw, double x, double y, double z)
		{
			this.file1 = file1;
			this.pitch = pitch;
			this.yaw = yaw;
			this.x = x;
			this.y = y;
			this.z = z;
			this.pin = true;
		}
	}

	private static final java.util.List<View> views = new java.util.ArrayList<>();
	private static boolean viewSetupDone;
	private static boolean fillWaitDone; // standard views: wait for the LOD field to fill first
	private static final int FILL_WAIT_TIMEOUT_TICKS = 3000; // ~150s before shooting anyway
	private static boolean noSpawn; // NOSPAWN token: show the world's own formations only
	private static boolean bigFormation; // BIG token: spawn a large stratus deck (LOD test, step 2)
	private static boolean fastClouds; // FAST token: crank the cloud speed (wind-drift test, step 4)
	private static long waitUntilTick = -1; // game tick at which the second (motion) shot fires
	private static long firstTick = -1; // game time of the first frame with a player
	private static final int POST_VIEW_FRAMES = 240; // settle time after switching views

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

	private static void spawnTestBolt(Minecraft mc)
	{
		forceNoon(mc);
		dev.nonamecrackers2.simpleclouds.client.renderer.WorldEffects effects =
				SimpleCloudsRenderer.getOptionalInstance().map(SimpleCloudsRenderer::getWorldEffectsManager).orElse(null);
		if (effects == null)
		{
			LOGGER.warn("[DEVSHOT] bolt test: no world effects manager");
			return;
		}
		double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
		effects.spawnLightning(new net.minecraft.core.BlockPos((int) px + 2, (int) (py + 40), (int) pz + 2),
				false, 12345, 3, 5, 2.0F, 1.5F, 20.0F, 160.0F);
		LOGGER.info("[DEVSHOT] bolt test: spawn at ({}, {}, {})", (int) (px + 3), (int) (py + 40), (int) pz);
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
	 * Step 2 (LOD) visual test: a large stratus deck (radius 1200 cloud units = 9600
	 * blocks) centered on the camera. The LOD field extends to ~10,500 blocks, so the
	 * distant coarse chunks (lod 4/8) are exercised, not just the near fine ones.
	 */
	private static void spawnBigStratus(Minecraft mc)
	{
		forceNoon(mc);
		try
		{
			ClientCloudManager manager = (ClientCloudManager) CloudManager.get(mc.level);
			if (manager == null)
			{
				LOGGER.warn("[DEVSHOT] BIG: no client cloud manager");
				return;
			}
			CloudType[] types = ClientSideCloudTypeManager.getInstance().getIndexedCloudTypes();
			if (types == null || types.length == 0)
			{
				LOGGER.warn("[DEVSHOT] BIG: no cloud types");
				return;
			}
			float px = (float) (mc.player.getX() / 8.0);
			float pz = (float) (mc.player.getZ() / 8.0);
			for (CloudType t : types)
			{
				String name = t.id().toString();
				if (!name.endsWith("stratus") || name.endsWith("nimbostratus"))
					continue;
				CloudRegion r = new CloudRegion(t.id(), new Vec2(0.0F, 0.0F), 0.0F, 0.0F,
					px, pz, 1200.0F, 0.0F, 1.0F, 240000, 20, 9);
				manager.getCloudGenerator().addCloud(r, CloudGenerator.Order.USE_WEIGHT);
				LOGGER.info("[DEVSHOT] BIG: spawned stratus deck r=1200u (9600 blocks) at {}x{}",
						px, pz);
				return;
			}
			LOGGER.warn("[DEVSHOT] BIG: no stratus type found");
		}
		catch (Throwable t)
		{
			LOGGER.warn("[DEVSHOT] BIG spawn failed", t);
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
			// worldBaseY = 0: density probe only counts instances (box-local space).
			gen.generate(x0, 0, z0, x0 + 16, 64, z0 + 16, 8.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1, 0.0F, oc, tc, 0, sc);
			return (int) oc[0];
		}
		catch (Throwable t)
		{
			return -1;
		}
	}

	// ------------------------------------------------------------------
	// Standard views (A-E): terrain probes + deterministic view setup
	// ------------------------------------------------------------------

	/** Terrain surface height at (x, z) ignoring liquids; -1 when the chunk is not loaded. */
	private static double terrainTop(Minecraft mc, int x, int z)
	{
		if (mc.level.getChunk(x >> 4, z >> 4) == null)
			return -1;
		return mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z); // 26.2: was MOTION_BLOCKING_NO_LIQUID
	}

	/** Whether the column ahead is open sea (terrain well below the sea level of 63). */
	private static boolean isOpenWater(Minecraft mc, int cx, int cz, double ang)
	{
		int run = 0;
		for (int s = 8; s <= 16; s++) // 128..256 blocks ahead in 16-block steps
		{
			int x = cx + (int) Math.round(Math.cos(ang) * s * 16);
			int z = cz + (int) Math.round(Math.sin(ang) * s * 16);
			double top = terrainTop(mc, x, z);
			if (top < 0)
				continue; // unloaded column: cannot confirm, cannot break the run
			if (top < 63)
				run++;
			else
				run = 0;
			if (run >= 4) // >= 64 continuous blocks of open sea
				return true;
		}
		return false;
	}

	/**
	 * Probe origin: the player's block XZ (NOT its Y: the dev client persists the
	 * player's last pinned position between runs, e.g. y=260 after a view-D run,
	 * and its gamemode to creative). The player's XZ is stable in the CloudClean
	 * world and — crucially — chunk loading follows the player, so a scan around
	 * the world spawn (usually kilometres away) would only see unloaded columns.
	 */
	private static int[] probeOrigin(Minecraft mc)
	{
		return new int[] { mc.player.getBlockX(), mc.player.getBlockZ() };
	}

	/**
	 * Finds a sea-level beach: standing surface (heightmap) at y 63..72 near the
	 * coast, with open water reachable in some direction. Deterministic scan of
	 * the world spawn column first, then Chebyshev rings in 16-block steps.
	 * The heightmap value is the standing surface Y (first empty slot), so the
	 * camera eye goes at surface + 1.62. Returns {x, eyeY, z, yawDegrees} or null.
	 */
	private static double[] findBeach(Minecraft mc)
	{
		int[] o = probeOrigin(mc);
		int px = o[0];
		int pz = o[1];
		for (int r = 0; r <= 20; r++)
		{
			for (int a = 0; a < 16; a++)
			{
				if (r == 0 && a != 0)
					continue;
				double ang = Math.toRadians(a * 22.5);
				int cx = px + (int) Math.round(Math.cos(ang) * r * 16);
				int cz = pz + (int) Math.round(Math.sin(ang) * r * 16);
				double surface = terrainTop(mc, cx, cz);
				if (surface < 63 || surface > 72)
					continue; // must be land at sea level (unloaded or high ground rejected)
				if (mc.level.getBlockState(new net.minecraft.core.BlockPos(cx, (int) surface, cz)).isSolid())
					continue; // the surface slot must be standable (air or water)
				for (int wa = 0; wa < 16; wa++)
				{
					double wang = Math.toRadians(wa * 22.5);
					if (isOpenWater(mc, cx, cz, wang))
					{
						// MC yaw: 0 = +Z, 90 = -X; view direction (cos wang, sin wang)
						// -> yaw = atan2(-dx, dz) in degrees (setYRot takes degrees).
						float yaw = (float) Math.toDegrees(Math.atan2(-Math.cos(wang), Math.sin(wang)));
						LOGGER.info("[DEVSHOT] view A beach: {}x{} (surface {}), feet y {}, facing yaw {} (origin {}x{})",
								cx, cz, surface, surface, (int) yaw, px, pz);
					// The pin sets the player FEET; the camera eye ends up at surface + 1.62.
						return new double[] { cx, surface, cz, yaw };
					}
				}
			}
		}
		LOGGER.warn("[DEVSHOT] no sea-level beach found near world spawn ({}, {}); falling back to the player column", px, pz);
		return null;
	}

	/** Finds higher land (surface y 66..97) for view B; deterministic ring scan. Returns {x, z} or null. */
	private static double[] findLand(Minecraft mc)
	{
		int[] o = probeOrigin(mc);
		int px = o[0];
		int pz = o[1];
		for (int r = 0; r <= 20; r++)
		{
			for (int a = 0; a < 8; a++)
			{
				if (r == 0 && a != 0)
					continue;
				double ang = Math.toRadians(a * 45.0);
				int cx = px + (int) Math.round(Math.cos(ang) * r * 16);
				int cz = pz + (int) Math.round(Math.sin(ang) * r * 16);
				double surface = terrainTop(mc, cx, cz);
				if (surface < 66 || surface > 97)
					continue;
				if (mc.level.getBlockState(new net.minecraft.core.BlockPos(cx, (int) surface, cz)).isSolid())
					continue;
				LOGGER.info("[DEVSHOT] view B land: {}x{} (surface {}) (origin {}x{})", cx, cz, surface, px, pz);
				return new double[] { cx, cz };
			}
		}
		LOGGER.warn("[DEVSHOT] no land found near world spawn ({}, {}); falling back to the beach position", px, pz);
		return null;
	}

	/** Server-side creative + flying + invulnerable so pinned views hold their altitude. */
	private static void holdInAir(Minecraft mc)
	{
		try
		{
			IntegratedServer server = mc.getSingleplayerServer();
			if (server == null)
				return;
			net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayer(mc.player.getUUID());
			if (sp == null)
				return;
			sp.setGameMode(GameType.CREATIVE);
			sp.getAbilities().flying = true;
			sp.getAbilities().invulnerable = true;
		}
		catch (Throwable t)
		{
			LOGGER.warn("[DEVSHOT] holdInAir failed (the per-frame pin still holds the camera)", t);
		}
	}

	/** Probes the terrain, builds the queued views, and moves to the first one. */
	private static void setupViews(Minecraft mc)
	{
		viewSetupDone = true;
		fillWaitDone = false;
		forceNoon(mc);
		double[] beach = findBeach(mc);
		if (beach == null)
		{
			int[] o = probeOrigin(mc);
			beach = new double[] { o[0], 70.0, o[1], 0.0 };
		}
		double[] land = findLand(mc);
		if (land == null)
			land = new double[] { beach[0], beach[2] };
		for (View v : views)
		{
			if (v.file1.endsWith("A.png") || v.file1.endsWith("C.png") || v.file1.endsWith("E1.png"))
			{
				v.x = beach[0];
				v.y = beach[1];
				v.z = beach[2];
				v.yaw = (float) beach[3];
			}
			else if (v.file1.endsWith("B.png"))
			{
				v.x = land[0];
				v.y = 100.0;
				v.z = land[1];
				if (Float.isNaN(v.yaw))
					v.yaw = 0.0F;
			}
			else if (v.file1.endsWith("D.png"))
			{
				v.x = beach[0];
				v.y = 260.0;
				v.z = beach[2];
				v.yaw = (float) beach[3];
			}
		}
		holdInAir(mc);
		switchToView(mc, 0);
		// The pre-setup guard held framesLeft at 1; now that the pin is applied,
		// restart the full settle so the camera (which follows the player with a
		// one-tick lag) converges before the first shot.
		framesLeft = framesTotal;
		if (bigFormation)
			spawnBigStratus(mc);
		else if (!noSpawn)
			spawnTestFormation(mc);

		// FAST: crank the cloud speed so the wind drift is visible over a 10s motion test
		// (step 4). The default speed (1.0) drifts <2 blocks in 10s, too slow to see.
		if (fastClouds)
		{
			CloudManager manager = CloudManager.get(mc.level);
			if (manager != null)
			{
				manager.setCloudSpeed(32.0F);
				LOGGER.info("[DEVSHOT] FAST: cloud speed set to 32x for the drift test");
			}
		}
	}

	private static int viewIdx = -1;

	private static void switchToView(Minecraft mc, int idx)
	{
		viewIdx = idx;
		View v = views.get(idx);
		shotAngle = v.pitch;
		shotYaw = v.yaw;
		if (v.pin)
		{
			mc.player.teleportSetPosition(new net.minecraft.world.entity.PositionMoveRotation(
					new net.minecraft.world.phys.Vec3(v.x, v.y, v.z), net.minecraft.world.phys.Vec3.ZERO,
					v.yaw, v.pitch), java.util.EnumSet.noneOf(net.minecraft.world.entity.Relative.class));
			LOGGER.info("[DEVSHOT] pin {}: requested {}x{}x{} -> actual {}x{}x{} (xRot {})",
					v.file1, v.x, v.y, v.z, mc.player.getX(), mc.player.getY(), mc.player.getZ(),
					(int) mc.player.getXRot());
		}
		LOGGER.info("[DEVSHOT] view {}: {} at {}x{}x{} pitch {} deg, yaw {} deg",
				idx, v.file1, v.x, v.y, v.z, (int) v.pitch, (int) v.yaw);
	}

	private static View currentView()
	{
		return views.get(viewIdx);
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
				// xRot (first) and yaw (second); letters A-E queue standard views;
				// keywords switch test scenes.
				framesLeft = Integer.parseInt(parts[0]);
				framesTotal = framesLeft;
				int numericSeen = 0;
				for (int i2 = 1; i2 < parts.length; i2++)
				{
					String part = parts[i2];
					if (part.equalsIgnoreCase("NOSHADOW"))
					{
						dev.nonamecrackers2.simpleclouds.client.renderer.v2.CloudsDrawPipeline.TERRAIN_SHADOWS_ENABLED = false;
						continue;
					}
					if (part.equalsIgnoreCase("NOSPAWN"))
					{
						noSpawn = true;
						continue;
					}
					if (part.equalsIgnoreCase("BIG"))
					{
						bigFormation = true;
						continue;
					}
					if (part.equalsIgnoreCase("FAST"))
					{
						fastClouds = true;
						continue;
					}
					if (part.equalsIgnoreCase("SHADOWTEST"))
					{
						shadowTest = true;
						if (Float.isNaN(shotAngle))
							shotAngle = -45.0F; // default: 45 deg up (clouds overhead)
						continue;
					}
					if (part.equalsIgnoreCase("OPTIONS"))
					{
						// Probe: open the 26.2 options screen right after the
						// screenshot is taken (the MixinOptionsScreen TAIL inject
						// logs every widget position).
						mc.setScreenAndShow(new net.minecraft.client.gui.screens.options.OptionsScreen(null, mc.options, false));
						continue;
					}
					if (part.equalsIgnoreCase("BOLT"))
					{
						boltTest = true;
						if (Float.isNaN(shotAngle))
							shotAngle = -20.0F;
						continue;
					}
					if (part.length() == 1)
					{
						char c = Character.toUpperCase(part.charAt(0));
						if (c >= 'A' && c <= 'E')
						{
							View v = new View("devshot-" + c + ".png", 0.0F, 0.0F, 0, 0, 0);
							switch (c)
							{
							case 'A': v.pitch = 0.0F; break;          // horizon over water
							case 'B': v.pitch = -15.0F; break;        // landscape from y=100
							case 'C': v.pitch = -90.0F; break;        // straight up
							case 'D': v.pitch = -20.0F; break;        // inside/above the layer
							case 'E':
								v.pitch = 0.0F;
								v.file1 = "devshot-E1.png";
								v.file2 = "devshot-E2.png";
								v.waitTicks = 200; // 10 s
								break;
							}
							views.add(v);
							continue;
						}
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
			catch (Exception e) { framesLeft = 240; framesTotal = 240; }
			LOGGER.info("[DEVSHOT] requested: {} frames, {} standard views, noSpawn={}",
					framesLeft, views.size(), noSpawn);
		}
		if (mc.player == null)
			return;

		// Standard views: probe terrain + teleport a few seconds after join (tick-based:
		// the render FPS varies, and the terrain around the target must be loaded first;
		// the test formation then spawns at the FINAL camera position).
		if (firstTick < 0)
			firstTick = mc.level.getGameTime();
		if (!views.isEmpty() && !viewSetupDone && mc.level.getGameTime() - firstTick >= 60)
			setupViews(mc);

		// Verification helper (automated loop only, i.e. devshot.request existed): the
		// world's formations may have drifted far outside the render band, so make sure
		// one exists above the player for the screenshot to verify cloud rendering.
		if (views.isEmpty() && !testSpawned && framesLeft <= 230) // spawn early: band regen needs ~40 frames
		{
			testSpawned = true;
			if (shadowTest)
				setupShadowTest(mc);
			else if (boltTest)
				spawnTestBolt(mc);
			else
				spawnTestFormation(mc);
		}
		if (!saved)
		{
			savedXRot = mc.player.getXRot();
			savedYRot = mc.player.getYRot();
			saved = true;
		}
		if (!views.isEmpty() && viewIdx >= 0)
		{
			View v = currentView();
			shotAngle = v.pitch;
			shotYaw = v.yaw;
			// Teleport ONCE per view switch (in switchToView), not every frame:
			// a per-frame teleportSetPosition left the player's xRotO stuck at its
			// pre-setup value and the camera rotation would not converge.
		}
		mc.player.setXRot(shotAngle);
		if (!Float.isNaN(shotYaw))
			mc.player.setYRot(shotYaw);
		if (boltTest && framesLeft > 0 && framesLeft % 30 == 0)
			spawnTestBolt(mc); // keep a young bolt alive for the 240-frame shot

		// Second shot of the motion view (E): fire on a game-tick boundary, not frames.
		if (waitUntilTick > 0)
		{
			if (mc.level.getGameTime() >= waitUntilTick)
			{
				waitUntilTick = -1;
				shoot(mc, currentView().file2);
				finishOrAdvance(mc);
			}
			return;
		}
		// Standard views: never shoot before the view setup (probe + teleport) ran.
		if (!views.isEmpty() && viewIdx < 0)
		{
			framesLeft = 1;
			return;
		}
		// First standard view: wait for the LOD field to fill (step 2) so the distant
		// coarse chunks are present, not just the near fine ones. Timeout as a guard.
		if (!views.isEmpty() && viewIdx == 0 && !fillWaitDone)
		{
			SimpleCloudsRenderer r = SimpleCloudsRenderer.getInstance();
			float frac = r != null ? r.getChunkFillFraction() : 1.0F;
			if (frac >= 0.97F || mc.level.getGameTime() - firstTick >= FILL_WAIT_TIMEOUT_TICKS)
			{
				fillWaitDone = true;
				framesLeft = framesTotal; // full settle after the fill
				LOGGER.info("[DEVSHOT] LOD field {}% filled ({} chunks), settling {} frames",
						Math.round(frac * 100), r != null ? "" : "", framesTotal);
			}
			else
			{
				return; // hold the countdown until the field is filled
			}
		}
		if (--framesLeft > 0)
			return;

		if (views.isEmpty())
		{
			// Legacy single-shot path.
			mc.player.setXRot(savedXRot); // restore the user's view
			mc.player.setYRot(savedYRot);
			done = true;
			Screenshot.grab(mc.gameDirectory, "devshot.png", mc.gameRenderer.mainRenderTarget(), 1,
					message -> LOGGER.info("[DEVSHOT] saved screenshots/devshot.png ({})", message.getString()));
		}
		else
		{
			View v = currentView();
			shoot(mc, v.file1);
			if (v.waitTicks > 0)
			{
				waitUntilTick = mc.level.getGameTime() + v.waitTicks;
				return;
			}
			finishOrAdvance(mc);
		}
		try { Files.deleteIfExists(request); }
		catch (Exception ignored) {}
	}

	/** After a shot: either start the wait for the view's second shot, switch to the
	 * next queued view, or finish the run (restore the user's view). */
	private static void finishOrAdvance(Minecraft mc)
	{
		if (viewIdx + 1 < views.size())
		{
			switchToView(mc, viewIdx + 1);
			framesLeft = POST_VIEW_FRAMES;
			return;
		}
		mc.player.setXRot(savedXRot);
		mc.player.setYRot(savedYRot);
		done = true;
	}

	private static void shoot(Minecraft mc, String name)
	{
		// Pin the clock so standard views (and the motion test E1/E2) differ only by the
		// cloud drift, not by the advancing sun (forceNoon is applied once at setup).
		forceNoon(mc);
		var cam = mc.gameRenderer.mainCamera();
		CloudManager cm = CloudManager.get(mc.level);
		float sx = cm != null ? cm.getScrollX() : 0.0F;
		float sy = cm != null ? cm.getScrollY() : 0.0F;
		float sz = cm != null ? cm.getScrollZ() : 0.0F;
		LOGGER.info("[DEVSHOT] shooting {}: cam pos {}x{}x{} camXRot {} camYRot {} (player xRot {} yRot {}, xRotO {} viewXRot(0.5) {}) scroll (drift) {}x{}x{}",
				name, cam.position().x, cam.position().y, cam.position().z,
				(int) cam.xRot(), (int) cam.yRot(),
				(int) mc.player.getXRot(), (int) mc.player.getYRot(),
				(int) mc.player.xRotO, (int) mc.player.getViewXRot(0.5F), sx, sy, sz);
		Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1,
				message -> LOGGER.info("[DEVSHOT] saved screenshots/{} ({})", name, message.getString()));
	}
}

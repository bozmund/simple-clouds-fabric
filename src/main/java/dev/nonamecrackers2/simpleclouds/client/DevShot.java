package dev.nonamecrackers2.simpleclouds.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import dev.nonamecrackers2.simpleclouds.client.cloud.ClientSideCloudTypeManager;
import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.ChunkBufferPool;
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
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.core.HolderLookup;
import net.minecraft.client.gui.screens.Screen;
import java.util.function.Function;
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
 * <li>{@code F} above: the beach XZ held at y=400 (above the 156..324 cloud
 * volume), pitch +45 down &rarr; {@code devshot-F.png} (A3: the true "view from above")</li>
 * <li>{@code OVL0} disable the overlay passes (shadow map + terrain shadows,
 * storm fog, atmospheric layer) for the whole run (A3 isolation tool).</li>
 * <li>{@code E} motion: view A three times, 200 game ticks (10 s) apart &rarr;
 * {@code devshot-E1.png} / {@code devshot-E2.png} / {@code devshot-E3.png}</li>
 * <li>{@code NOSPAWN} skip the automatic test-formation spawn (shows the world's own
 * persisted formations only).</li>
 * <li>{@code LOOP} (A1 memory proof) repeat the standard view sequence until
 * {@code devshot.request} is deleted.</li>
 * <li>{@code FPSLOG} (step 7 real-profile performance) log the game's own FPS /
 * frame time every 60 s of game time while the run is active.</li>
 * <li>{@code STORM} (storm plan step 0) spawn a cumulonimbus formation at a
 * fixed offset from the player and queue the storm views S1-S5: S1 ground
 * facing the formation's near edge, S2 from under it looking up, S3 beside it
 * at y=260, S4 above its east side looking 30 degrees down toward the center,
 * S5 a 30-frame / 2 s (60 s) ground sequence while
 * lightning runs (four strikes at 200/1500/3000/8000 blocks are forced at
 * 5/15/25/35 s; every strike — natural or forced — logs distance + flash).
 * Implies no standard test formation (the storm IS the scene). The storm is full
 * size from the start and the scroll angle is fixed, so runs are comparable;
 * {@code STORMGROW} brings back the growing (non-repeatable) formation.</li>
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
	// FPSLOG token (step 7 real-profile performance check): while a run is
	// active, log the game's own FPS / frame time every 60 s of game time.
	private static boolean fpsLog;
	private static long fpsLogNextTick = -1;
	// STORM token (storm plan step 0): the chosen formation center (cloud units)
	// and the S5 forced-strike schedule (storm plan step 1 proof needs strikes at
	// known distances: 200/1500/3000/8000 blocks north of the camera).
	private static boolean hold; // HOLD token (step 7): after the last view, keep the run active on that view so FPSLOG can sample one scene
	private static boolean storm;
	// Repeatable STORM scene (Claude, 2026-09-15): the fixture spawns at full size and barely
	// shrinks during a run (a region ages 20x faster while no spawn region sees it, so with the
	// old 72000-tick lifetime S3/S4/S5 showed a different storm size in every run), and the
	// scroll angle -- which the saved world carries over from the previous run -- starts at the
	// same value (2.88 rad = the phase -97,26 of Codex's settled 09:35 evidence).
	// STORMGROW restores the old growth (300 ticks, lifetime 72000).
	private static boolean stormGrow;
	private static final int STORM_FIXTURE_LIFETIME = 2_000_000; // worst case 40000 ticks/run = 2% shrink
	private static final float STORM_SCROLL_ANGLE = 2.88F;
	private static double stormCxCu = Double.NaN;
	private static double stormCzCu = Double.NaN;
	// ---- Step 3: superflat reference scene (26.2 port captures) ----
	// CREATEFLAT <name> <seed>: on the title screen, create + load a superflat world
	// (26.2 WorldOpenFlows.createFreshLevel) before the views run; the flat views
	// A/C/D/F then stand at the spawn origin (identical positions to the 1.20.1
	// reference run). UNDERSTORM adds the U-01 view (41 frames, from directly under
	// the storm cell center, pitch -20).
	private static boolean flatWorld;
	private static boolean flatCreateAttempted;
	private static boolean understorm;
	private static int seqFrame; // 1 = file1 taken, then 2..seqCount
	private static long seqNextFrameTick = -1;
	private static final int[] STRIKE_DISTANCES = { 200, 1500, 3000, 8000 };
	private static long stormStrikeTick = -1; // game tick of the next forced strike
	private static int stormStrikeIdx = 0; // index into STRIKE_DISTANCES (4 = done)

	// ---- standard views (A-E) ----

	/** One queued camera view: where to stand, where to look, what to capture. */
	private static final class View
	{
		String file1;
		String file2; // second shot of this view (motion), null otherwise
		String file3; // third shot of this view (motion, A2), null otherwise
		float pitch;
		float yaw;
		double x, y, z;
		boolean pin; // teleport the player to (x,y,z) every frame
		long waitTicks; // after file1: wait this many game ticks, then take file2
		long waitTicks2; // after file2: wait this many game ticks, then take file3
		// S5 (storm plan): after file1, take seqCount-1 more frames named
		// file1-with-suffix (-02 .. -NN), seqInterval game ticks apart.
		int seqCount;
		long seqInterval;
		// Plan item 2 (STORM S2): stand on the terrain at (x, z) itself, not at the probe
		// origin's ground height (reused 800 blocks away, it put the camera among plants /
		// inside hills). Resolved once that column's chunk is loaded.
		boolean groundAtTarget;
		boolean groundResolved;

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
	// A3 diagnosis (temporary isolation tool, keep): OVL0 disables the overlay
	// passes (shadow map + terrain shadows, storm fog, atmospheric layer) so the
	// voxel field alone can be inspected; OVL1 enables them (default).
	private static boolean overlaysOn = true;
	private static boolean noSpawn; // NOSPAWN token: show the world's own formations only
	private static boolean bigFormation; // BIG token: spawn a large stratus deck (LOD test, step 2)
	private static boolean fastClouds; // FAST token: crank the cloud speed (wind-drift test, step 4)
	// A1 (memory proof): LOOP cycles the standard views forever (each cycle teleports
	// between LOD grid cells, so chunks keep regenerating and moving). The run ends
	// when devshot.request is deleted externally (the script's stop switch).
	private static boolean loop;
	private static int loopCycle;
	private static long waitUntilTick = -1; // game tick at which the pending (motion) shot fires
	private static int pendingShot = 0; // 2 = file2 pending, 3 = file3 pending (motion view)
	private static long firstTick = -1; // game time of the first frame with a player
	private static final int POST_VIEW_FRAMES = 240; // settle time after switching views
	// Plan item 2: a view is only shot once the terrain around the camera is loaded and
	// compiled, stable for TERRAIN_STABLE_FRAMES frames; after TERRAIN_WAIT_TIMEOUT_NS the
	// view is refused with an error instead of accepting a half-loaded screenshot.
	private static final int TERRAIN_STABLE_FRAMES = 30;
	private static final long TERRAIN_WAIT_TIMEOUT_NS = 90_000_000_000L;
	private static final long TERRAIN_SETTLE_NS = 3_000_000_000L;
	private static int terrainStableFrames;
	private static long terrainWaitStartNs = -1;
	private static boolean terrainReadyLogged;

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
	 * Storm plan step 0: a cumulonimbus at a fixed offset from the player (nominal
	 * center 250 cloud units = 2000 blocks NORTH, radius 200 units = 1600 blocks).
	 * The world noise field is dense in some places and empty in others, so the
	 * exact center is the densest of a 4x4 probe grid around the nominal offset
	 * (±30/±40 units); the choice is logged and deterministic per world. Full size
	 * from the first tick with a 2,000,000-tick lifetime and a fixed scroll angle, so
	 * every run shows the same storm (STORMGROW: the old 300-tick growth — the
	 * original's 6000-10000 is 5-8 minutes, too slow for a test — and lifetime 72000,
	 * the original exist_ticks max), stretch 0.5 (original spawn
	 * range 0.3-0.6), order weight 1000 (the original's). The near edge of the
	 * formation then sits ~400 blocks north of the S1/S5 camera, inside the
	 * 32-chunk render distance.
	 */
	private static void spawnStormFormation(Minecraft mc)
	{
		forceNoon(mc);
		stormCxCu = Double.NaN;
		stormCzCu = Double.NaN;
		try
		{
			ClientCloudManager manager = (ClientCloudManager) CloudManager.get(mc.level);
			if (manager == null)
			{
				LOGGER.warn("[DEVSHOT] STORM: no client cloud manager");
				return;
			}
			CloudType[] types = ClientSideCloudTypeManager.getInstance().getIndexedCloudTypes();
			CloudType nimbus = null;
			if (types != null)
			{
				for (CloudType t : types)
				{
					if (t.id().toString().endsWith("cumulonimbus"))
					{
						nimbus = t;
						break;
					}
				}
			}
			if (nimbus == null)
			{
				LOGGER.warn("[DEVSHOT] STORM: no cumulonimbus cloud type loaded");
				return;
			}
			float px = (float) (mc.player.getX() / 8.0);
			float pz = (float) (mc.player.getZ() / 8.0);
			List<CpuCloudGenerator.CloudLayerGroup> groups = SimpleCloudsRenderer.dataDrivenGroups();
			Map<net.minecraft.resources.Identifier, Integer> typeToGroup = SimpleCloudsRenderer.dataDrivenGroupIndices();
			float[] dxs = { -30.0F, -10.0F, 10.0F, 30.0F };
			float[] dzs = { -280.0F, -260.0F, -240.0F, -220.0F };
			int bestI = -1, bestJ = -1, bestCount = 0;
			for (int i = 0; i < dxs.length; i++)
			{
				for (int j = 0; j < dzs.length; j++)
				{
					int count = probeDensity(groups, typeToGroup, nimbus, px + dxs[i], pz + dzs[j]);
					if (count > bestCount)
					{
						bestCount = count;
						bestI = i;
						bestJ = j;
					}
				}
			}
			if (bestI < 0 || bestCount <= 0)
			{
				LOGGER.warn("[DEVSHOT] STORM: noise field empty around the nominal offset ({}x{}); spawning at the nominal center anyway",
					px, pz - 250.0F);
				bestI = 1;
				bestJ = 1;
			}
			float cx = px + dxs[bestI];
			float cz = pz + dzs[bestJ];
			int lifetime = stormGrow ? 72000 : STORM_FIXTURE_LIFETIME;
			int grow = stormGrow ? 300 : 0;
			CloudRegion region = new CloudRegion(nimbus.id(), new Vec2(0.001F, 0.0F), 0.0F, 0.0F,
				cx, cz, 200.0F, 0.0F, 0.5F, lifetime, grow, 1000);
			// A replay must not be refused because a previous run filled the
			// saved generator's formation limit. Sync below copies this fixture
			// and the scroll angle to the integrated server too.
			manager.getCloudGenerator().setClouds(List.of(region));
			manager.setScrollAngle(STORM_SCROLL_ANGLE);
			if (manager.getClouds().contains(region))
			{
				stormCxCu = cx;
				stormCzCu = cz;
				LOGGER.info("[DEVSHOT] STORM: spawned {} (r=200u, stretch 0.5, grow {}t, lifetime {}t, scroll angle {}) at cloud units {}x{} (offset {}x{} from player, probe density {})",
					nimbus.id(), grow, lifetime, STORM_SCROLL_ANGLE, cx, cz, cx - px, cz - pz, bestCount);
			}
			else
			{
				LOGGER.warn("[DEVSHOT] STORM: addCloud refused the cumulonimbus");
			}
		}
		catch (Throwable t)
		{
			LOGGER.warn("[DEVSHOT] STORM: storm formation spawn failed", t);
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
			// A1: the probe writes into pooled buffers (16x64x16 cells is tiny).
			ChunkBufferPool pool = new ChunkBufferPool();
			java.nio.ByteBuffer opaque = pool.borrow(256 * 1024);
			java.nio.ByteBuffer transparent = pool.borrow(256 * 1024);
			// worldBaseY = 0: density probe only counts instances (box-local space).
			java.nio.ByteBuffer[] out = gen.generate(x0, 0, z0, x0 + 16, 64, z0 + 16, 8.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1, 0.0F,
					opaque, transparent, pool::grow, oc, tc, 0, new float[] { x0 + 8, z0 + 8 }, sc);
			pool.release(out[0]);
			pool.release(out[1]);
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
		if (!mc.level.hasChunk(x >> 4, z >> 4))
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
		double[] beach;
		if (flatWorld)
		{
			// Step 3: superflat reference scene — views stand at the spawn origin
			// (identical positions to the 1.20.1 reference run: A/C at eye =
			// terrainTop + 5, D at y=260, F at y=400, all at the origin XZ).
			int[] o = probeOrigin(mc);
			beach = new double[] { o[0], terrainTop(mc, o[0], o[1]) + 5.0, o[1], 0.0 };
		}
		else
		{
			beach = findBeach(mc);
			if (beach == null)
			{
				int[] o = probeOrigin(mc);
				// A previous above-cloud test persists its altitude. Ground views must
				// use the terrain height, not that saved camera position.
				beach = new double[] { o[0], terrainTop(mc, o[0], o[1]) + 3.0, o[1], 0.0 };
			}
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
				v.y = Math.max(100.0, terrainTop(mc, (int)land[0], (int)land[1]) + 16.0);
				v.z = land[1];
				if (Float.isNaN(v.yaw))
					v.yaw = 0.0F;
			}
			else if (v.file1.startsWith("devshot-SHAKE-"))
			{
				v.x = beach[0];
				v.y = beach[1] + 24.0;
				v.z = beach[2];
				v.yaw = (float)beach[3];
			}
			else if (v.file1.endsWith("D.png"))
			{
				v.x = beach[0];
				v.y = 260.0;
				v.z = beach[2];
				v.yaw = (float) beach[3];
			}
			else if (v.file1.endsWith("F.png"))
			{
				// A3: the true "view from above" — the cloud volume spans y 156..324,
				// so y=260 (view D) is INSIDE it; y=400 is above the top.
				v.x = beach[0];
				v.y = 400.0;
				v.z = beach[2];
				v.yaw = (float) beach[3];
			}
			else if (v.file1.endsWith("G.png"))
			{
				// Step 5: close-up of the cloud BASE from below (y=120, ~40 blocks
				// under the 156.. volume base) — the transparent edge cubes form the
				// silhouette of the base, so soft (alpha) edges must be visible.
				v.x = beach[0];
				v.y = 120.0;
				v.z = beach[2];
				v.yaw = (float) beach[3];
			}
			else if (v.file1.endsWith("H.png"))
			{
				// Step 6: slightly above ground, looking ~12° down — the terrain
				// fills the lower frame while the deterministic SHADOWTEST formation
				// (spawned above this position) and its shadow are both visible:
				// coverage + soft edges in one shot.
				v.x = beach[0];
				v.y = beach[1] + 30.0;
				v.z = beach[2];
				v.yaw = (float) beach[3];
			}
		}
		// STORM: spawn the cumulonimbus FIRST — the S view positions (S2-S4)
		// depend on the chosen formation center.
		if (storm)
			spawnStormFormation(mc);
		if (storm && (!Double.isFinite(stormCxCu) || !Double.isFinite(stormCzCu)))
		{
			LOGGER.error("[DEVSHOT] STORM fixture failed; refusing invalid camera positions");
			done = true;
			return;
		}
		if (storm)
		{
			double px = mc.player.getX(), pz = mc.player.getZ();
			double pxCu = px / 8.0, pzCu = pz / 8.0;
			for (View v : views)
			{
				String f = v.file1;
				if (f.endsWith("S1.png") || f.endsWith("S5-01.png"))
				{
					// Ground level at the probe origin, facing north (the near edge
					// of the formation is ~400 blocks away, inside the 32-chunk
					// render distance).
					v.x = beach[0];
					v.y = beach[1];
					v.z = beach[2];
				}
				else if (f.endsWith("S2.png"))
				{
					// Under the formation, ~150 cloud units from its center.
					v.x = px;
					v.y = beach[1];
					v.z = pz - 100 * 8.0;
					v.groundAtTarget = true; // y is resolved from the terrain there (plan item 2)
				}
				else if (f.endsWith("S3.png"))
				{
					// Beside it (east of center, 20u inside the 200u edge), y=260
					// (in the 0..256-block base layers), facing the column.
					v.x = (stormCxCu + 180.0) * 8.0;
					v.y = 260.0;
					v.z = stormCzCu * 8.0;
				}
				else if (f.endsWith("S4.png"))
				{
					// Above it, obliquely (Claude, 2026-09-15): straight down from Y 2300
					// over the center, the full-size cell (repeatable fixture) filled the
					// frame with top faces -- 100 % white in every run. Now 250 units
					// (2000 blocks) east of the center, Y 2600 (~400-550 blocks over the
					// 2048-2176-block volume top), 30 degrees down toward the center: the
					// tops, the cell outline and the sky beyond it stay in view.
					v.x = (stormCxCu + 250.0) * 8.0;
					v.y = 2600.0;
					v.z = stormCzCu * 8.0;
				}
				else if (f.endsWith("U-01.png"))
				{
					// Step 3 UNDERSTORM: directly under the cell center, ground + 1,
					// looking up at the storm (identical to the 1.20.1 reference run).
					v.x = stormCxCu * 8.0;
					v.z = stormCzCu * 8.0;
					v.groundAtTarget = true; // y resolves to terrainTop + 1 at the center
				}
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
		else if (storm)
			LOGGER.info("[DEVSHOT] STORM scene: cumulonimbus center at cloud units {}x{} (r=200u); skipping the standard test formation",
					stormCxCu, stormCzCu);
		else if (!noSpawn)
			spawnTestFormation(mc);

		// FAST: crank the cloud speed so the wind drift is visible over a 10s motion test
		// (step 4). The default speed (1.0) drifts <2 blocks in 10s, too slow to see.
		if (fastClouds)
		{
			CloudManager manager = CloudManager.get(mc.level);
			if (manager != null)
			{
				// Client-only mode reads the config directly; setCloudSpeed alone
				// is ignored there. This is in-memory dev-test state, not saved config.
				dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig.CLIENT.speedModifier.set(32.0);
				manager.setCloudSpeed(32.0F);
				LOGGER.info("[DEVSHOT] FAST: effective cloud speed {}", manager.getCloudSpeed());
			}
		}
		// Singleplayer is still server-authoritative. A client-only test formation
		// or speed is overwritten by the next normal sync packet, producing a
		// synthetic whole-sky jump that must not be blamed on the mesh cache.
		var clientManager = CloudManager.get(mc.level);
		IntegratedServer testServer = mc.getSingleplayerServer();
		if (clientManager != null && views.stream().anyMatch(v -> v.file1.startsWith("devshot-SHAKE-")))
		{
			// A replay starts from one named formation and the same noise phase.
			// Saved random formations otherwise make normal/FAST runs incomparable.
			for (CloudType type : ClientSideCloudTypeManager.getInstance().getIndexedCloudTypes())
			{
				if (!type.id().toString().equals("simpleclouds:cumulus")) continue;
				View motion = views.stream().filter(v -> v.file1.startsWith("devshot-SHAKE-")).findFirst().orElseThrow();
				CloudRegion region = new CloudRegion(type.id(), new Vec2(0.0F, 0.0F), 0.0F, 0.0F,
						(float)(motion.x/8.0), (float)(motion.z/8.0), 1200.0F, 0.0F, 1.0F, 240000, 1, 9);
				clientManager.getCloudGenerator().setClouds(List.of(region));
				clientManager.setScrollAngle(0.75F);
				LOGGER.info("[DEVSHOT] fixed SHAKE scene: one cumulus formation, initial angle=0.75");
				break;
			}
		}
		if (clientManager != null && testServer != null)
		{
			var formationTags = clientManager.getClouds().stream().map(CloudRegion::toTag).toList();
			float angle = clientManager.getScrollAngle();
			float speed = fastClouds ? 32.0F : 1.0F;
			dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig.CLIENT.speedModifier.set((double)speed);
			clientManager.setCloudSpeed(speed);
			testServer.execute(() -> {
				var authoritative = CloudManager.get(testServer.overworld());
				if (authoritative == null) return;
				authoritative.getCloudGenerator().setClouds(formationTags.stream().map(CloudRegion::new).toList());
				authoritative.setScrollAngle(angle);
				authoritative.setCloudSpeed(speed);
				if (authoritative instanceof dev.nonamecrackers2.simpleclouds.common.world.ServerCloudManager serverManager)
				{
					serverManager.queueSync(dev.nonamecrackers2.simpleclouds.common.world.SyncType.BASE_PROPERTIES);
					serverManager.queueSync(dev.nonamecrackers2.simpleclouds.common.world.SyncType.CLOUD_FORMATIONS);
				}
				LOGGER.info("[DEVSHOT] authoritative test scene synchronized; speed={}", speed);
			});
		}
	}

	private static int viewIdx = -1;
	private static long viewGenerationBase;
	private static long viewStartedTick;

	private static void switchToView(Minecraft mc, int idx)
	{
		viewIdx = idx;
		viewGenerationBase = SimpleCloudsRenderer.getInstance().getPublishedBatchCount();
		viewStartedTick = mc.level.getGameTime();
		terrainStableFrames = 0;
		terrainWaitStartNs = System.nanoTime();
		terrainReadyLogged = false;
		View v = views.get(idx);
		shotAngle = v.pitch;
		shotYaw = v.yaw;
		if (v.pin)
		{
			syncViewToServer(mc, v);
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

	private static void syncViewToServer(Minecraft mc, View view)
	{
		var server = mc.getSingleplayerServer();
		if (server == null) return;
		var uuid = mc.player.getUUID();
		double x = view.x, y = view.y, z = view.z;
		float yaw = view.yaw, pitch = view.pitch;
		server.execute(() -> {
			var player = server.getPlayerList().getPlayer(uuid);
			if (player != null)
			{
				player.connection.teleport(x, y, z, yaw, pitch);
				LOGGER.info("[DEVSHOT] server view synchronized at {}x{}x{}", x, y, z);
			}
		});
	}

	/** Called once per rendered world frame, after the clouds were drawn. */
	/**
	 * Step 3: superflat reference scene. On the title screen (no level yet), if the
	 * devshot request carries a {@code CREATEFLAT <name> <seed>} token, create + load a
	 * superflat world (the 26.2 {@code WorldOpenFlows.createFreshLevel} path) so the flat
	 * views A/C/D/F can stand at the spawn origin. Called from the client tick; guarded
	 * to run once.
	 */
	public static void maybeCreateFlatWorld(Minecraft mc)
	{
		if (!"1".equals(System.getenv("SIMPLECLOUDS_DEV")))
			return;
		if (mc.level != null || flatCreateAttempted || done)
			return;
		Path request = mc.gameDirectory.toPath().resolve("devshot.request");
		if (!Files.exists(request))
			return;
		String content;
		try
		{
			content = Files.readString(request).trim();
		}
		catch (Exception e)
		{
			return;
		}
		String[] parts = content.split("\\s+");
		String name = null;
		long seed = 0;
		for (int i = 0; i + 2 < parts.length; i++)
		{
			if (parts[i].equalsIgnoreCase("CREATEFLAT"))
			{
				name = parts[i + 1];
				try
				{
					seed = Long.parseLong(parts[i + 2]);
				}
				catch (NumberFormatException ignored) { seed = 0; }
			}
		}
		if (name == null)
			return;
		flatCreateAttempted = true;
		flatWorld = true;
		LOGGER.info("[DEVSHOT] CREATEFLAT: creating superflat world '{}' seed {} (26.2 createFreshLevel)", name, seed);
		try
		{
			LevelSettings settings = new LevelSettings(name, GameType.CREATIVE,
					LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT);
			WorldOptions options = new WorldOptions(seed, false, false);
			Function<HolderLookup.Provider, WorldDimensions> provider = registries -> registries
					.lookupOrThrow(Registries.WORLD_PRESET)
					.getOrThrow(WorldPresets.FLAT) // thin default superflat (1 bedrock + 2 dirt + 1 grass, surface y=-60) to match the 1.20.1 reference, NOT the thick FLAT_ALL_DIMENSIONS
					.value()
					.createWorldDimensions();
			Screen parent = mc.gui.screen();
			mc.createWorldOpenFlows().createFreshLevel(name, settings, options, provider, parent);
		}
		catch (Throwable t)
		{
			LOGGER.error("[DEVSHOT] CREATEFLAT failed", t);
			done = true;
		}
	}

	public static void onWorldFrame()
	{
		// A stale request file must never turn a normal play session into a test.
		// Only the isolated developer launcher explicitly opts into automation.
		if (!"1".equals(System.getenv("SIMPLECLOUDS_DEV")))
			return;
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
				// A DevShot run is active: enable the (throttled) generator proof logs
				// so chunk generation / brightness can be verified from the log.
				dev.nonamecrackers2.simpleclouds.client.renderer.v2.CpuCloudGenerator.devProofLogging = true;
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
					if (part.equalsIgnoreCase("LOOP"))
					{
						loop = true;
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
					if (part.equalsIgnoreCase("OVL0"))
					{
						// A3 diagnosis: disable the overlay passes (shadow map +
						// terrain shadows, storm fog, atmospheric layer) — inspect the
						// voxel field alone.
						overlaysOn = false;
						dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer.setOverlaysEnabled(false);
						continue;
					}
					if (part.equalsIgnoreCase("HOLD"))
					{
						// Step 7: keep the last view pinned and the run active (no "done") so FPSLOG keeps sampling one scene.
						hold = true;
						continue;
					}
					if (part.equalsIgnoreCase("HIDEFLASH"))
					{
						// Storm plan step 6: enable the vanilla "Hide Sky Flashes"
						// accessibility option (26.2: Options.hideLightningFlash) for
						// this run; the flash pass must not fire.
						mc.options.hideLightningFlash().set(true);
						LOGGER.info("[DEVSHOT] HIDEFLASH: vanilla Hide Sky Flashes enabled for this run");
						continue;
					}
					if (part.equalsIgnoreCase("NOFOG"))
					{
						// Step 6 diagnosis: disable ONLY the storm-fog fullscreen
						// overlay (which darkens the whole screen when the camera is
						// under storm clouds) so the terrain cloud-shadow can be seen
						// on its own.
						dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer.setStormFogEnabled(false);
						continue;
					}
					if (part.equalsIgnoreCase("FOGDEBUG"))
					{
						// Plan item 3: storm fog debug view -- the reconstructed scene distance as
						// grey (sky white), to check the depth convention on a captured frame.
						SimpleCloudsRenderer.setDevStormFogDebug(1);
						continue;
					}
					if (part.equalsIgnoreCase("NOFLASH"))
					{
						// Plan item 3: storm fog without bolt light (A/B against the default).
						SimpleCloudsRenderer.setDevFogFlashes(false);
						continue;
					}
					if (part.equalsIgnoreCase("FLAT"))
					{
						// Step 8 diagnostic: disable the per-cube storm shading
						// (all cubes bright white) for an A/B comparison.
						dev.nonamecrackers2.simpleclouds.client.renderer.v2.CpuCloudGenerator.stormShading = false;
						continue;
					}
					if (part.equalsIgnoreCase("SHADNEAR"))
					{
						// Step 6 diagnostic: force the terrain-shadow start radius to
						// 0 (shadows from 32 blocks out) to isolate the shadow
						// pipeline from the original's distance-fade model.
						dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer.setDevMinRadius(0.0F);
						continue;
					}
					if (part.equalsIgnoreCase("SHADOWDBG"))
					{
						// Step 6 diagnostic: the terrain pass outputs the stored
						// shadow-map depth as red (1 = empty, ~0.75 = cloud,
						// green = outside the shadow volume).
						dev.nonamecrackers2.simpleclouds.client.renderer.v2.CloudsDrawPipeline.DEBUG_SHOW_DEPTH = 1.0F;
						continue;
					}
					if (part.equalsIgnoreCase("SHADOWDUMP"))
					{
						// Step 6 diagnostic: raw shadow-map dump by screen UV.
						dev.nonamecrackers2.simpleclouds.client.renderer.v2.CloudsDrawPipeline.DEBUG_SHOW_DEPTH = 2.0F;
						continue;
					}
					if (part.equalsIgnoreCase("SHADOWWORLD"))
					{
						// Step 6 diagnostic: reconstructed worldPos (camera-relative) as color.
						dev.nonamecrackers2.simpleclouds.client.renderer.v2.CloudsDrawPipeline.DEBUG_SHOW_DEPTH = 3.0F;
						continue;
					}
					if (part.equalsIgnoreCase("BOLT"))
					{
						boltTest = true;
						if (Float.isNaN(shotAngle))
							shotAngle = -20.0F;
						continue;
					}
					if (part.equalsIgnoreCase("FPSLOG"))
					{
						// Step 7 (real profile): periodic self-measured FPS so the
						// performance check does not need keyboard input or the HUD.
						fpsLog = true;
						continue;
					}
					if (part.equalsIgnoreCase("STORMGROW"))
					{
						// With STORM: let the fixture grow over 300 ticks and age with the
						// old 72000-tick lifetime (not repeatable run to run).
						stormGrow = true;
						continue;
					}
					if (part.equalsIgnoreCase("STORM"))
					{
						// Storm plan step 0: cumulonimbus at a fixed offset + the
						// storm views S1-S5 (the storm replaces the standard test
						// formation; request "240 STORM" for the storm scene).
						storm = true;
						View s1 = new View("devshot-S1.png", 0.0F, 180.0F, 0, 0, 0); // ground, facing the near edge (north)
						View s2 = new View("devshot-S2.png", -90.0F, 0.0F, 0, 0, 0); // under the formation, looking up
						View s3 = new View("devshot-S3.png", -20.0F, 90.0F, 0, 0, 0); // beside it at y=260, facing west (toward center)
						View s4 = new View("devshot-S4.png", 30.0F, 90.0F, 0, 0, 0); // above the east side, 30 deg down, facing west (toward center)
						View s5 = new View("devshot-S5-01.png", 0.0F, 180.0F, 0, 0, 0); // ground sequence, 60 frames x 0.25 s
						s5.seqCount = 60;
						s5.seqInterval = 5; // storm plan step 1 proof: the gated flash flickers
							// (original's pow(rand,2) duty ~30%), so 2 s cadence misses it -
							// sample at 0.25 s across the 15 s window instead.
						views.add(s1);
						views.add(s2);
						views.add(s3);
						views.add(s4);
						views.add(s5);
						continue;
					}
					if (part.equalsIgnoreCase("UNDERSTORM"))
					{
						// Step 3: same storm fixture as STORM, but a single view U-01 from
						// directly under the cell center (ground + 1, pitch -20, yaw 0),
						// 41 frames at 0.25 s (identical to the 1.20.1 reference run).
						storm = true;
						understorm = true;
						View u1 = new View("devshot-U-01.png", -20.0F, 0.0F, 0, 0, 0);
						u1.seqCount = 41;
						u1.seqInterval = 5;
						views.add(u1);
						continue;
					}
					if (part.equalsIgnoreCase("SHAKE"))
					{
						View motion = new View("devshot-SHAKE-01.png", -35.0F, 0.0F, 0, 0, 0);
						motion.seqCount = 41;
						motion.seqInterval = 5;
						views.add(motion);
						continue;
					}
					if (part.length() == 1)
					{
						char c = Character.toUpperCase(part.charAt(0));
						if (c >= 'A' && c <= 'H')
						{
							View v = new View("devshot-" + c + ".png", 0.0F, 0.0F, 0, 0, 0);
							switch (c)
							{
							case 'A': v.pitch = 0.0F; break;          // horizon over water
							case 'B': v.pitch = -15.0F; break;        // landscape from y=100
							case 'C': v.pitch = -90.0F; break;        // straight up
							case 'D': v.pitch = -20.0F; break;        // inside the layer (y=260)
							case 'F': v.pitch = 45.0F; break;         // above the layer, looking down
								case 'G': v.pitch = -45.0F; break;        // look UP at the cloud base from below (step 5: soft edges)
								case 'H': v.pitch = -12.0F; break;        // SHADOWTEST: terrain + cloud (step 6: shadow coverage/soft edges)
							case 'E':
								v.pitch = 0.0F;
								v.file1 = "devshot-E1.png";
								v.file2 = "devshot-E2.png";
								v.file3 = "devshot-E3.png"; // A2: three motion shots
								v.waitTicks = 200; // 10 s
								v.waitTicks2 = 200; // 10 s
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
			LOGGER.info("[DEVSHOT] requested: {} frames, {} standard views, noSpawn={}, loop={}",
					framesLeft, views.size(), noSpawn, loop);
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
		if (done) return;

		// FPSLOG: one line per 1200 ticks (60 s of game time). getFps()/
		// getFrameTimeNs() are Minecraft's own counters, independent of the
		// devshot camera pinning.
		if (fpsLog && fpsLogNextTick < 0)
			fpsLogNextTick = mc.level.getGameTime() + 1200;
		if (fpsLog && mc.level.getGameTime() >= fpsLogNextTick)
		{
			long frameNs = mc.getFrameTimeNs();
			LOGGER.info("[DEVSHOT-FPS] fps={} frameNs={} ({} ms) tick {}", mc.getFps(), frameNs, frameNs / 1_000_000L,
					mc.level.getGameTime());
			fpsLogNextTick = mc.level.getGameTime() + 1200;
		}

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
			if (v.groundAtTarget && !v.groundResolved)
			{
				// Step 3: a merely-present chunk has an uncomputed WORLD_SURFACE heightmap
				// (it defaults to the min build height), which on the y=-60 superflat pinned
				// the camera underground (S2 / U-01). A height-based guard (top >= 0) also
				// fails (the flat surface is negative), and the client does not load a column
				// 800 blocks out to FULL in 90 s. So: accept the column only once its
				// heightmap is computed, i.e. reads ABOVE the min build height.
				int ix = (int) Math.floor(v.x), iz = (int) Math.floor(v.z);
				if (mc.level.hasChunk(ix >> 4, iz >> 4))
				{
					double top = terrainTop(mc, ix, iz);
					if (top > mc.level.getMinY()) // heightmap computed (not the min-height default)
					{
						v.y = top + 1.0;
						v.groundResolved = true;
						syncViewToServer(mc, v);
						terrainStableFrames = 0;
						LOGGER.info("[DEVSHOT] {}: terrain at the target column {}x{} is Y {}; camera pinned at Y {}",
								v.file1, ix, iz, (int) top, v.y);
					}
				}
			}
			if (v.pin)
			{
				// Re-pin EVERY frame (step 7, real profile): a one-shot teleport per
				// view switch left the client/server position desynced in the
				// 329-mod profile, so the player drifted or fell between the switch
				// and the shot (view B rendered at ground level, F mid-fall).
				// The old per-frame bug (xRotO stuck -> the camera rotation never
				// converged) is fixed by pinning the *O fields explicitly.
				double dx = mc.player.getX() - v.x;
				double dy = mc.player.getY() - v.y;
				double dz = mc.player.getZ() - v.z;
				if (dx * dx + dy * dy + dz * dz > 1.0E-4
						|| mc.player.getXRot() != v.pitch
						|| mc.player.getYRot() != v.yaw)
				{
					mc.player.teleportSetPosition(new net.minecraft.world.entity.PositionMoveRotation(
							new net.minecraft.world.phys.Vec3(v.x, v.y, v.z), net.minecraft.world.phys.Vec3.ZERO,
							v.yaw, v.pitch), java.util.EnumSet.noneOf(net.minecraft.world.entity.Relative.class));
					mc.player.xRotO = v.pitch;
					mc.player.yRotO = v.yaw;
					if (dy < -0.5F) // a fall means creative flight was lost — restore it
						holdInAir(mc);
				}
			}
		}
		mc.player.setXRot(shotAngle);
		if (!Float.isNaN(shotYaw))
			mc.player.setYRot(shotYaw);
		if (boltTest && framesLeft > 0 && framesLeft % 30 == 0)
			spawnTestBolt(mc); // keep a young bolt alive for the 240-frame shot

		// S5 (storm plan step 0/1): forced strikes at KNOWN distances from the camera
		// (200/1500/3000/8000 blocks north, 10 s apart) so the flash behavior has
		// deterministic proof data; every strike — forced or natural — is logged by
		// WorldEffects.spawnLightning with distance + applied flash.
		if (storm && pendingShot == 4 && stormStrikeTick > 0
				&& stormStrikeIdx < STRIKE_DISTANCES.length
				&& mc.level.getGameTime() >= stormStrikeTick)
		{
			int dist = STRIKE_DISTANCES[stormStrikeIdx];
			double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
			dev.nonamecrackers2.simpleclouds.client.renderer.WorldEffects effects =
					SimpleCloudsRenderer.getOptionalInstance().map(SimpleCloudsRenderer::getWorldEffectsManager).orElse(null);
			if (effects != null)
				effects.spawnLightning(new net.minecraft.core.BlockPos(
						(int) px, (int) (py + 100), (int) (pz - dist)),
						false, 12345 + dist, 4, 2, 300.0F, 20.0F, 20.0F, 90.0F);
			LOGGER.info("[DEVSHOT-LIGHTNING] FORCED strike at {} blocks north (dist index {})", dist, stormStrikeIdx);
			stormStrikeIdx++;
			stormStrikeTick = stormStrikeIdx < STRIKE_DISTANCES.length
					? mc.level.getGameTime() + 80 // 4 s between forced strikes (all inside the 15 s window)
					: -1;
		}

		// Second/third shots of the motion view (E): fire on game-tick boundaries,
		// not frames. A2: three shots (E1/E2/E3) 200 ticks (10 s) apart.
		if (waitUntilTick > 0)
		{
			if (mc.level.getGameTime() >= waitUntilTick)
			{
				View v = currentView();
				if (pendingShot == 2)
				{
					shoot(mc, v.file2);
					if (v.file3 != null && v.waitTicks2 > 0)
					{
						pendingShot = 3;
						waitUntilTick = mc.level.getGameTime() + v.waitTicks2;
						return;
					}
					waitUntilTick = -1;
					finishOrAdvance(mc);
				}
				else if (pendingShot == 4)
				{
					// S5 sequence frame (devshot-S5-01.png is file1; 02..30 follow).
					seqFrame++;
					String prefix = v.file1.substring(0, v.file1.length() - "01.png".length());
					shoot(mc, String.format("%s%02d.png", prefix, seqFrame));
					if (seqFrame < v.seqCount)
					{
						waitUntilTick = mc.level.getGameTime() + v.seqInterval;
						return;
					}
					waitUntilTick = -1;
					stormStrikeTick = -1;
					finishOrAdvance(mc);
				}
				else
				{
					shoot(mc, v.file3);
					waitUntilTick = -1;
					finishOrAdvance(mc);
				}
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
			boolean fresh = r != null && r.getPublishedBatchCount() >= viewGenerationBase + 2;
			boolean grown = mc.level.getGameTime() - viewStartedTick >= (storm ? 400 : 100);
			if ((frac >= 0.97F && fresh && grown) || mc.level.getGameTime() - firstTick >= FILL_WAIT_TIMEOUT_TICKS)
			{
				fillWaitDone = true;
				// STORM: the devshot cumulonimbus needs 300t to grow; in a warm world
				// the field can fill in seconds, long before it is fully grown (the
				// NOFOG run of 2026-09-13 shot S2 four seconds after spawn = empty
				// sky). Hold the view at least 20 s after the fill in that case.
				// Frame-based, not tick-based: the dev client is uncapped (60-100+ FPS),
				// so 1200 frames ~= 12-20 s, covering the formation's 300-tick (15 s)
				// growth at any render rate.
				int settle = storm ? Math.max(framesTotal, 1200) : framesTotal;
				framesLeft = settle;
				LOGGER.info("[DEVSHOT] LOD field {}% filled ({} chunks), settling {} frames",
						Math.round(frac * 100), r != null ? "" : "", settle);
			}
			else
			{
				return; // hold the countdown until the field is filled
			}
		}
		if (--framesLeft > 0)
			return;
		// Plan item 2: never shoot while the terrain around the camera is still loading.
		if (!views.isEmpty() && viewIdx >= 0)
		{
			int gate = terrainGate(mc);
			if (gate < 0)
			{
				finishOrAdvance(mc); // refused (logged as an error): no screenshot for this view
				return;
			}
			if (gate == 0)
			{
				framesLeft = 1;
				return;
			}
		}
		if (viewIdx > 0 && SimpleCloudsRenderer.getInstance().getPublishedBatchCount() <= viewGenerationBase
				&& mc.level.getGameTime() - viewStartedTick < FILL_WAIT_TIMEOUT_TICKS)
		{
			framesLeft = 1;
			return;
		}

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
			if (v.seqCount > 0)
			{
				// S5 (storm plan): a frame every seqInterval ticks for seqCount frames.
				seqFrame = 1;
				pendingShot = 4;
				waitUntilTick = mc.level.getGameTime() + v.seqInterval;
				stormStrikeIdx = 0;
				stormStrikeTick = storm ? mc.level.getGameTime() + 20 : -1;
				return;
			}
			if (v.waitTicks > 0)
			{
				pendingShot = 2;
				waitUntilTick = mc.level.getGameTime() + v.waitTicks;
				return;
			}
			finishOrAdvance(mc);
		}
		// In LOOP/HOLD mode the request file is the external stop switch — keep it
		// (the loop/hold ends when the script deletes it); one-shot runs delete it
		// as before. (Step 7: deleting it during HOLD made the HOLD branch fail on
		// the next frame and ended the run immediately after the last view.)
		if ((!loop && !hold) || done)
		{
			try { Files.deleteIfExists(request); }
			catch (Exception ignored) {}
		}
	}

	/**
	 * Plan item 2: 1 when the terrain around the pinned camera is loaded and compiled (the
	 * camera's column resolved, the 7x7 chunks around it present, every visible section
	 * compiled and the compile queue empty) for TERRAIN_STABLE_FRAMES consecutive frames;
	 * 0 while still loading; -1 when it did not settle within TERRAIN_WAIT_TIMEOUT_NS
	 * (the view is refused and the failure is logged as an error for the log gate).
	 */
	private static int terrainGate(Minecraft mc)
	{
		View v = currentView();
		int cx = Mth.floor(mc.player.getX()) >> 4, cz = Mth.floor(mc.player.getZ()) >> 4;
		boolean loaded = true;
		for (int dx = -3; dx <= 3 && loaded; dx++)
			for (int dz = -3; dz <= 3 && loaded; dz++)
				loaded = mc.level.hasChunk(cx + dx, cz + dz);
		// The terrain the game will actually draw for this view = its visibleSections:
		// exactly the sections that pass the game's own frustum + occlusion culling, and
		// exactly what lands in the framebuffer (mod clouds are a separate GPU pass and
		// never appear as level sections). The old gate sampled ground columns on a fixed
		// grid and demanded 2/3 of those sections be compiled — on 26.2 that never passes
		// for horizon views, because most sampled sections are culled from the draw set by
		// the occlusion graph and never compiled even when the frame is complete (verified
		// 2026-09-17: a forced shot at the 90 s timeout showed fully rendered terrain, no
		// holes, while the old gate refused with 16/281 "compiled").
		int drawSet = 0, notSettled = 0;
		long nowMs = System.currentTimeMillis();
		var visible = mc.levelRenderer.visibleSections();
		for (int i2 = 0; i2 < visible.size(); i2++)
		{
			var rs = visible.get(i2);
			drawSet++;
			// "Settled": mesh compiled AND faded in (visibility ~1.0). A freshly compiled
			// section fades in over the chunk-section fade-in time; shooting mid-fade would
			// capture translucent (ghosted) terrain.
			if (rs.getSectionMesh() == net.minecraft.client.renderer.chunk.CompiledSectionMesh.UNCOMPILED
					|| rs.getVisibility(nowMs) < 0.95F)
				notSettled++;
		}
		boolean terrainInView = loaded && notSettled == 0;
		long waited = terrainWaitStartNs >= 0 ? System.nanoTime() - terrainWaitStartNs : 0;
		var renderer = SimpleCloudsRenderer.getInstance();
		boolean cloudReady = renderer.getPublishedBatchCount() >= viewGenerationBase + 2
				&& renderer.isCaptureFieldSettled();
		boolean ready = (!v.groundAtTarget || v.groundResolved) && terrainInView
				&& cloudReady
				&& mc.levelRenderer.hasRenderedAllSections()
				&& waited >= TERRAIN_SETTLE_NS;
		terrainStableFrames = ready ? terrainStableFrames + 1 : 0;
		if (terrainStableFrames >= TERRAIN_STABLE_FRAMES)
		{
			if (!terrainReadyLogged)
			{
				terrainReadyLogged = true;
				LOGGER.info("[DEVSHOT] {}: terrain loaded and compiled after {} ms at {}x{}x{}; visible sections {} ({} unsettled), cloud fill {}, render pitch/yaw {}/{}",
						v.file1, waited / 1_000_000, (int) mc.player.getX(), (int) mc.player.getY(), (int) mc.player.getZ(),
						drawSet, notSettled, String.format("%.2f", renderer.getChunkFillFraction()),
						(int) mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.xRot,
						(int) mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.yRot);
			}
			return 1;
		}
		if (waited > TERRAIN_WAIT_TIMEOUT_NS)
		{
			LOGGER.error("Simple Clouds ERROR: [DEVSHOT] {}: scene still loading after {} ms (column resolved {}, chunks loaded {}, unsettled visible sections {}/{}, sections rendered {}, cloud ready {}, cloud fill {}, batches {}/{}); view refused",
					v.file1, waited / 1_000_000, !v.groundAtTarget || v.groundResolved, loaded, notSettled, drawSet,
					mc.levelRenderer.hasRenderedAllSections(),
					cloudReady, String.format("%.2f", renderer.getChunkFillFraction()),
					renderer.getPublishedBatchCount(), viewGenerationBase + 2);
			return -1;
		}
		return 0;
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
		// Step 7: HOLD re-pins the LAST view and stays active (FPS sampling).
		if (hold && Files.exists(mc.gameDirectory.toPath().resolve("devshot.request")))
		{
			switchToView(mc, views.size() - 1);
			framesLeft = POST_VIEW_FRAMES;
			return;
		}
		// A1: LOOP restarts the whole view sequence (the view-setup positions are
		// already pinned, so this just re-teleports; the fill-wait stays latched).
		if (loop && Files.exists(mc.gameDirectory.toPath().resolve("devshot.request")))
		{
			loopCycle++;
			LOGGER.info("[DEVSHOT] LOOP cycle {} — restarting the view sequence", loopCycle);
			switchToView(mc, 0);
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
		// Step 7 (real profile): the per-frame pin must have held the camera; a
		// drift > 2 blocks from the pinned eye position means the shot is suspect.
		if (viewIdx >= 0)
		{
			View v = currentView();
			double dxc = cam.position().x - v.x;
			double dyc = cam.position().y - (v.y + 1.62);
			double dzc = cam.position().z - v.z;
			if (dxc * dxc + dyc * dyc + dzc * dzc > 4.0)
				LOGGER.warn("[DEVSHOT] {} camera drifted from the pinned view ({}x{}x{}): cam at {}x{}x{} — shot suspect",
						name, v.x, v.y, v.z, cam.position().x, cam.position().y, cam.position().z);
		}
		CloudManager cm = CloudManager.get(mc.level);
		float sx = cm != null ? cm.getScrollX() : 0.0F;
		float sy = cm != null ? cm.getScrollY() : 0.0F;
		float sz = cm != null ? cm.getScrollZ() : 0.0F;
		LOGGER.info("[DEVSHOT] state speed={} {}", cm != null ? cm.getCloudSpeed() : 0,
				SimpleCloudsRenderer.getInstance().generationDiagnostic() + " " + SimpleCloudsRenderer.getInstance().meshDiagnostic());
		LOGGER.info("[DEVSHOT] shooting {}: cam pos {}x{}x{} camXRot {} camYRot {} (player xRot {} yRot {}, xRotO {} viewXRot(0.5) {}) scroll (drift) {}x{}x{}",
				name, cam.position().x, cam.position().y, cam.position().z,
				(int) cam.xRot(), (int) cam.yRot(),
				(int) mc.player.getXRot(), (int) mc.player.getYRot(),
				(int) mc.player.xRotO, (int) mc.player.getViewXRot(0.5F), sx, sy, sz);
		Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1,
				message -> LOGGER.info("[DEVSHOT] saved screenshots/{} ({})", name, message.getString()));
	}
}

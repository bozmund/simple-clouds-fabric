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
	private static boolean saved;
	private static boolean testSpawned;
	private static float shotAngle = -90.0F; // straight up by default

	private DevShot() {}

	private static void spawnTestFormation(Minecraft mc)
	{
		try
		{
			// The save's world time can be night (SIGKILL exits don't save it); force
			// NOON on the integrated server so the screenshot can actually show clouds.
			// 26.2 replaced dayTime with the data-driven WorldClock system.
			IntegratedServer server = mc.getSingleplayerServer();
			if (server == null)
			{
				LOGGER.info("[DEVSHOT] no integrated server (not singleplayer?); skipping clock override");
			}
			else
			{
				ServerClockManager clock = server.clockManager();
				if (clock == null)
				{
					LOGGER.info("[DEVSHOT] no ServerClockManager in the overworld data storage; skipping clock override");
				}
				else
				{
					net.minecraft.core.Holder<net.minecraft.world.clock.WorldClock> holder =
							server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(WorldClocks.OVERWORLD);
					if (!clock.moveToTimeMarker(holder, ClockTimeMarkers.NOON))
						clock.setTotalTicks(holder, 6000L);
					LOGGER.info("[DEVSHOT] moved the overworld clock to NOON for the screenshot");
				}
			}
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
			float[] candX = new float[] { 0.0F, 10.0F, -10.0F, 20.0F, -20.0F, 14.0F, -14.0F, 26.0F };
			float[] candZ = new float[] { 0.0F, 10.0F, -10.0F, -20.0F, 20.0F, 14.0F, -14.0F, -26.0F };
			List<CpuCloudGenerator.CloudLayerGroup> groups = SimpleCloudsRenderer.dataDrivenGroups();
			Map<net.minecraft.resources.Identifier, Integer> typeToGroup = SimpleCloudsRenderer.dataDrivenGroupIndices();
			int best = 0;
			int bestCount = -1;
			CloudType bestType = null;
			for (CloudType t : types)
			{
				if (t.id().toString().endsWith("empty"))
					continue;
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
			if (bestType == null)
			{
				LOGGER.warn("[DEVSHOT] no renderable cloud type found");
				return;
			}
			for (int spawned = 0; spawned < 3; spawned++)
			{
				CloudType t = bestType;
				CloudRegion region = new CloudRegion(t.id(), new Vec2(0.001F, 0.0F), 0.0F, 0.0F,
					px + candX[best], pz + candZ[best], 20.0F, 0.0F, 1.2F, 24000, 10, spawned);
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
			gen.setRegions(List.of(new CpuCloudGenerator.RegionMask(cx, cz, 20.0F, 1.0F, 0.0F, 0.0F, 1.0F, group)));
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
				framesLeft = Integer.parseInt(parts[0]);
				if (parts.length > 1)
					shotAngle = Float.parseFloat(parts[1]); // second token: camera xRot
			}
			catch (Exception e) { framesLeft = 240; }
			LOGGER.info("[DEVSHOT] requested: capturing after {} frames", framesLeft);
		}
		if (mc.player == null)
			return;
		// Verification helper (automated loop only, i.e. devshot.request existed): the
		// world's formations may have drifted far outside the render band, so make sure
		// one exists above the player for the screenshot to verify cloud rendering.
		if (!testSpawned && framesLeft <= 235)
		{
			testSpawned = true;
			spawnTestFormation(mc);
		}
		if (!saved)
		{
			savedXRot = mc.player.getXRot();
			saved = true;
		}
		mc.player.setXRot(shotAngle);
		if (--framesLeft > 0)
			return;
		mc.player.setXRot(savedXRot); // restore the user's view
		done = true;
		Screenshot.grab(mc.gameDirectory, "devshot.png", mc.gameRenderer.mainRenderTarget(), 1,
				message -> LOGGER.info("[DEVSHOT] saved screenshots/devshot.png ({})", message.getString()));
		try { Files.deleteIfExists(request); }
		catch (Exception ignored) {}
	}
}

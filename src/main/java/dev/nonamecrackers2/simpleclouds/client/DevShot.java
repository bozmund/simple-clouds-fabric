package dev.nonamecrackers2.simpleclouds.client;

import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

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

	private DevShot() {}

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
			try { framesLeft = Integer.parseInt(Files.readString(request).trim()); }
			catch (Exception e) { framesLeft = 240; }
			LOGGER.info("[DEVSHOT] requested: capturing after {} frames", framesLeft);
		}
		if (mc.player == null)
			return;
		if (!saved)
		{
			savedXRot = mc.player.getXRot();
			saved = true;
		}
		mc.player.setXRot(-90.0F); // look straight up at the cloud layer
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

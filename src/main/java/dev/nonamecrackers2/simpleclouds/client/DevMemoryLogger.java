package dev.nonamecrackers2.simpleclouds.client;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A1 (VISUAL-PARITY-PLAN addendum): dev-build memory telemetry.
 *
 * Started only when {@code SIMPLECLOUDS_DEV=1} (set by dev-relaunch.sh on the dev
 * client unit; the real profile never runs this). Every 30 s it logs:
 * <ul>
 * <li>JVM heap used/max,</li>
 * <li>direct memory (BufferPoolMXBean "direct": used, capacity, buffer count) — the
 * resource that OOM-killed the 2026-09-13 dev client,</li>
 * <li>process RSS (VmRSS from /proc/self/status on Linux).</li>
 * </ul>
 * The 30-minute A1 proof run reads these lines (plus an external
 * {@code systemctl --user show -p MemoryCurrent} sampler) and must show the
 * numbers LEVELLING OFF, not climbing.
 */
public final class DevMemoryLogger
{
	private static final Logger LOGGER = LogManager.getLogger("simpleclouds/DevMem");
	private static volatile boolean started;

	/** Starts the 30 s logging thread (no-op unless SIMPLECLOUDS_DEV=1). */
	public static void maybeStart()
	{
		if (started)
			return;
		if (!"1".equals(System.getenv("SIMPLECLOUDS_DEV")))
			return;
		started = true;
		Thread t = new Thread(DevMemoryLogger::logLoop, "simpleclouds-devmem");
		t.setDaemon(true);
		t.start();
	}

	private static void logLoop()
	{
		while (true)
		{
			try
			{
				Thread.sleep(30_000L);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				return;
			}
			try
			{
				MemoryMXBean heap = ManagementFactory.getMemoryMXBean();
				MemoryUsage hu = heap.getHeapMemoryUsage();
				// (JDK 25 dropped ManagementFactory.getBufferPoolMXBeans().)
				BufferPoolMXBean direct = null;
				for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class))
				{
					if ("direct".equals(pool.getName()))
						direct = pool;
				}
				long rssKb = readVmRssKb();
				StringBuilder sb = new StringBuilder(160);
				sb.append(String.format(java.util.Locale.ROOT,
						"[DEVMEM] heap %d/%d MB",
						hu.getUsed() / 1048576L, hu.getMax() / 1048576L));
				if (direct != null)
				{
					sb.append(String.format(java.util.Locale.ROOT,
							" | direct %d MB used / %d MB capacity / %d buffers",
							direct.getMemoryUsed() / 1048576L, direct.getTotalCapacity() / 1048576L,
							direct.getCount()));
				}
				if (rssKb > 0)
					sb.append(String.format(java.util.Locale.ROOT, " | RSS %.2f GB", rssKb / 1048576.0));
				LOGGER.info(sb.toString());
			}
			catch (Throwable t)
			{
				LOGGER.warn("[DEVMEM] telemetry failed", t);
			}
		}
	}

	/** VmRSS in kB from /proc/self/status (Linux; -1 elsewhere). */
	private static long readVmRssKb()
	{
		try
		{
			Path status = Path.of("/proc/self/status");
			if (!Files.exists(status))
				return -1;
			for (String line : Files.readAllLines(status))
			{
				if (line.startsWith("VmRSS:"))
				{
					String[] parts = line.split("\\s+");
					return Long.parseLong(parts[1]);
				}
			}
		}
		catch (Exception ignored) { }
		return -1;
	}
}

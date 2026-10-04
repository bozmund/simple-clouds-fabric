package dev.nonamecrackers2.simpleclouds.client;

import dev.nonamecrackers2.simpleclouds.client.world.ClientCloudManager;
import dev.nonamecrackers2.simpleclouds.common.world.CloudManager;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;

/** Observes actual region packets; never removes client clouds to force a pass. */
public final class SleepClientProbe {
    private static final boolean ENABLED = "1".equals(System.getenv("SIMPLECLOUDS_DEV"))
            && "1".equals(System.getenv("SIMPLECLOUDS_TEST_SLEEP"));
    private static boolean seen;
    private static boolean done;
    private SleepClientProbe() {}
    public static void tick(Minecraft client) {
        if (!ENABLED || done || client.level == null) return;
        var raw = CloudManager.get(client.level);
        if (!(raw instanceof ClientCloudManager manager) || !manager.hasReceivedSync()) return;
        var present = manager.getCloudGenerator().getClouds().stream().anyMatch(r ->
                r.getCloudTypeId().toString().equals("simpleclouds:cumulonimbus")
                && Math.abs(r.getPosX()) < 0.01 && Math.abs(r.getPosZ()) < 0.01);
        if (present && !seen) {
            seen = true;
            LogManager.getLogger("simpleclouds/SleepClientProbe").info("[SLEEP-CLIENT] nearby storm received");
        } else if (!present && seen) {
            done = true;
            LogManager.getLogger("simpleclouds/SleepClientProbe").info("[SLEEP-CLIENT] nearby storm removal synchronized");
        }
    }
}

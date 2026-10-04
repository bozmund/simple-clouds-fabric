package dev.nonamecrackers2.simpleclouds.client;

import dev.nonamecrackers2.simpleclouds.client.config.ClientServerConfig;
import dev.nonamecrackers2.simpleclouds.client.cloud.ClientSideCloudTypeManager;
import dev.nonamecrackers2.simpleclouds.client.world.ClientCloudManager;
import dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig;
import dev.nonamecrackers2.simpleclouds.common.world.CloudManager;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;

/** Explicit localhost multiplayer fixture, inert in ordinary launches. */
public final class DedicatedClientProbe {
    private static final boolean ENABLED = "1".equals(System.getenv("SIMPLECLOUDS_DEV"))
            && "1".equals(System.getenv("SIMPLECLOUDS_TEST_DEDICATED"));
    private static final org.apache.logging.log4j.Logger LOG = LogManager.getLogger("simpleclouds/DedicatedProbe");
    private static int stage, ticks;
    private static float initialSpeed;
    private static int initialHeight;
    private static long initialSeed;
    private static net.minecraft.client.multiplayer.ServerData reconnectData;
    private DedicatedClientProbe() {}
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void tick(Minecraft client) {
        if (!ENABLED || stage == 99) return;
        try {
            require(client.gameDirectory.toPath().toAbsolutePath().normalize().toString()
                    .matches("/home/jan/simple-clouds-fabric/build/dedicated-client-[AB]"), "unexpected test directory");
            if (++ticks > 2400) throw new AssertionError("multiplayer probe timeout stage=" + stage);
            if ((stage == 3 || stage == 5) && client.level == null) {
                // World unload can precede the channel's DISCONNECT callback.
                // Wait for that real callback, never clear the state in a probe.
                if (ClientServerConfig.get() != null && ticks < 100) return;
                require(ClientServerConfig.get() == null, "snapshot leaked after disconnect");
                require(!ClientSideCloudTypeManager.getInstance().hasReceivedSynced(), "cloud types leaked after disconnect");
                require(!SimpleCloudsConfig.SERVER_SPEC.isLoaded(), "remote session bound SERVER_SPEC");
                WeatherConnectionProbe.disconnected(client);
                if (stage == 3) {
                    LOG.info("[DEDICATED-PROBE] first disconnect cleared snapshot; reconnecting");
                    stage = 4; ticks = 0;
                    net.minecraft.client.gui.screens.ConnectScreen.startConnecting(
                            new net.minecraft.client.gui.screens.TitleScreen(), client,
                            net.minecraft.client.multiplayer.resolver.ServerAddress.parseString("127.0.0.1:25575"),
                            reconnectData, false, null);
                    return;
                }
                LOG.info("[DEDICATED-PROBE] disconnect cleared snapshot; PASS");
                stage = 99;
                client.stop();
                return;
            }
            if (client.level == null || client.player == null) return;
            require(client.getSingleplayerServer() == null, "not a dedicated connection");
            require(client.getCurrentServer() != null && "127.0.0.1:25575".equals(client.getCurrentServer().ip), "wrong endpoint");
            require(!SimpleCloudsConfig.SERVER_SPEC.isLoaded(), "SERVER_SPEC should remain unbound");
            var config = ClientServerConfig.get();
            var raw = CloudManager.get(client.level);
            if (config == null || !(raw instanceof ClientCloudManager manager) || !manager.hasReceivedSync()) return;
            require(ClientSideCloudTypeManager.getInstance().hasReceivedSynced(), "manager synchronized before cloud types");
            if (ticks % 100 == 0) LOG.info("[DEDICATED-PROBE] waiting stage={} blacklist={} vanilla={} speed={} height={} clock={} rain={}",
                    stage, config.whitelistAsBlacklist(), manager.shouldUseVanillaWeather(), manager.getCloudSpeed(),
                    manager.getCloudHeight(), client.level.getOverworldClockTime(), client.level.getRainLevel(1.0F));
            require(manager.getCloudMode() == config.cloudMode(), "mode does not use received snapshot");
            require(manager.getSingleModeCloudTypeRawId().equals(config.singleModeCloudType()), "type does not use received snapshot");
            if (stage == 0 && !config.whitelistAsBlacklist() && !manager.shouldUseVanillaWeather()
                    && ClientSideCloudTypeManager.getInstance().getCloudTypes().size() == 8) {
                initialSpeed = manager.getCloudSpeed(); initialHeight = manager.getCloudHeight();
                initialSeed = manager.getSeed(); reconnectData = client.getCurrentServer();
                WeatherConnectionProbe.connected(client);
                LOG.info("[DEDICATED-PROBE] joined: seed={} speed={} height={} types=8 serverSpecLoaded=false",
                        manager.getSeed(), initialSpeed, initialHeight);
                stage = 6; ticks = 0;
            } else if(stage == 6 && ClientSideCloudTypeManager.getInstance().getCloudTypes().size() == 10) {
                var types=ClientSideCloudTypeManager.getInstance().getCloudTypes();
                require(types.containsKey(net.minecraft.resources.Identifier.parse("codex:storm"))
                        && types.containsKey(net.minecraft.resources.Identifier.parse("codex:nested/storm")),"nested reload IDs lost");
                require(manager.getSeed()==initialSeed,"datapack reload changed world seed");
                LOG.info("[DEDICATED-PROBE] reload added both nested cloud types; seed/config preserved");
                stage = 7; ticks = 0;
            } else if(stage == 7 && ClientSideCloudTypeManager.getInstance().getCloudTypes().size() == 8) {
                require(!ClientSideCloudTypeManager.getInstance().getCloudTypes().containsKey(
                        net.minecraft.resources.Identifier.parse("codex:nested/storm")),"disabled datapack type retained");
                LOG.info("[DEDICATED-PROBE] reload removed scratch types; original type set restored");
                stage = 1; ticks = 0;
            } else if (stage == 1 && config.whitelistAsBlacklist() && manager.shouldUseVanillaWeather()
                    && manager.getCloudSpeed() == 0.37F && manager.getCloudHeight() == 1234
                    && client.level.getOverworldClockTime() % 24000 >= 12000 && client.level.getOverworldClockTime() % 24000 < 13000
                    && client.level.getRainLevel(1.0F) > 0.05F) {
                LOG.info("[DEDICATED-PROBE] changed: config, movement, height, night and vanilla rain received");
                stage = 2; ticks = 0;
            } else if (stage == 2 && !config.whitelistAsBlacklist() && !manager.shouldUseVanillaWeather()
                    && manager.getCloudSpeed() == initialSpeed && manager.getCloudHeight() == initialHeight
                    && client.level.getOverworldClockTime() % 24000 >= 1000 && client.level.getOverworldClockTime() % 24000 < 2000) {
                LOG.info("[DEDICATED-PROBE] restored: config, movement, height, day and custom weather authority");
                if(WeatherConnectionProbe.enabled()) {
                    WeatherConnectionProbe.armBeforeDisconnect(client);
                    LOG.info("[WEATHER-DIMENSION] armed old Overworld state; waiting for Nether transfer");
                    stage=8;ticks=0;return;
                }
                stage = 3; ticks = 0;
                // Use the same full network-disconnect path as the Pause menu;
                // disconnectWithSavingScreen only unloads the local world.
                WeatherConnectionProbe.armBeforeDisconnect(client);
                client.disconnectFromWorld(net.minecraft.network.chat.Component.literal("Dedicated fixture finished"));
            } else if(stage==8 && client.level.dimension().equals(net.minecraft.world.level.Level.NETHER)) {
                WeatherConnectionProbe.changedDimension(client);
                stage=9;ticks=0;
            } else if(stage==9 && client.level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)
                    && manager.getSeed()==initialSeed && !manager.shouldUseVanillaWeather()) {
                WeatherConnectionProbe.changedDimension(client);
                WeatherConnectionProbe.armBeforeDisconnect(client);
                stage=3;ticks=0;
                client.disconnectFromWorld(net.minecraft.network.chat.Component.literal("Weather dimension fixture finished"));
            } else if (stage == 4 && !config.whitelistAsBlacklist() && !manager.shouldUseVanillaWeather()
                    && manager.getCloudSpeed() == initialSpeed && manager.getCloudHeight() == initialHeight) {
                require(manager.getSeed() == initialSeed, "seed changed on reconnect");
                WeatherConnectionProbe.connected(client);
                LOG.info("[DEDICATED-PROBE] rejoin verified: fresh manager sync and original seed/config");
                stage = 5; ticks = 0;
                WeatherConnectionProbe.armBeforeDisconnect(client);
                client.disconnectFromWorld(net.minecraft.network.chat.Component.literal("Dedicated rejoin finished"));
            }
        } catch (Throwable failure) {
            stage = 99;
            LOG.error("Simple Clouds ERROR: dedicated client probe failed", failure);
            client.stop();
        }
    }
}

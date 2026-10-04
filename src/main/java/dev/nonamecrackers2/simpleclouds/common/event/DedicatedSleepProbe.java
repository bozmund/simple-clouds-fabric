package dev.nonamecrackers2.simpleclouds.common.event;

import java.util.List;
import java.util.Set;
import dev.nonamecrackers2.simpleclouds.common.cloud.CloudTypeDataManager;
import dev.nonamecrackers2.simpleclouds.common.cloud.region.CloudRegion;
import dev.nonamecrackers2.simpleclouds.common.cloud.spawning.CloudGenerator;
import dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig;
import dev.nonamecrackers2.simpleclouds.common.world.CloudManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec2;
import org.apache.logging.log4j.LogManager;

/** Actual bed/sleep hooks; only two named players in the accepted scratch server. */
public final class DedicatedSleepProbe {
    private static final boolean ENABLED = "1".equals(System.getenv("SIMPLECLOUDS_TEST_SLEEP"));
    private static final org.apache.logging.log4j.Logger LOG = LogManager.getLogger("simpleclouds/SleepProbe");
    private static int stage, ticks;
    private static CloudRegion nearby, distant;
    private static List<ServerPlayer> players;
    private static final BlockPos[] BEDS = {new BlockPos(1, 70, 0), new BlockPos(17, 70, 0)};
    private DedicatedSleepProbe() {}
    public static void register() {
        if (ENABLED) ServerTickEvents.END_SERVER_TICK.register(DedicatedSleepProbe::tick);
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void sleep(ServerPlayer player, BlockPos head) {
        var state = player.level().getBlockState(head);
        var bed = (AbstractBedBlock)state.getBlock();
        var result = player.startSleepInBed(bed, state, bed.getBedRule(player.level(), head), head);
        require(result.right().isPresent() && player.isSleeping(), "real sleep rejected: " + result);
    }
    private static void tick(MinecraftServer server) {
        if (stage == 99) return;
        try {
            require(server.isDedicatedServer() && server.getServerDirectory().toAbsolutePath().normalize().toString()
                    .equals("/home/jan/simple-clouds-fabric/build/dedicated-boundary-smoke"), "unsafe sleep fixture directory");
            require(server.getWorldData().getLevelName().equals("CodexDedicatedServer20261001"), "wrong sleep world");
            if (++ticks > 1800) throw new AssertionError("sleep timeout stage=" + stage);
            var level = server.overworld();
            var manager = CloudManager.get(level);
            if (stage == 0) {
                if (server.getPlayerCount() != 2) return;
                players = server.getPlayerList().getPlayers().stream()
                        .sorted(java.util.Comparator.comparing(p -> p.getGameProfile().name())).toList();
                require(players.get(0).getGameProfile().name().equals("CodexA")
                        && players.get(1).getGameProfile().name().equals("CodexB"), "unexpected players");
                require(SimpleCloudsConfig.SERVER_SPEC.isLoaded() && !manager.shouldUseVanillaWeather(), "custom weather required");
                level.getGameRules().set(GameRules.PLAYERS_SLEEPING_PERCENTAGE, 100, server);
                level.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set 1000");
                for (int i = 0; i < 2; i++) {
                    var head = BEDS[i]; var foot = head.west();
                    for (int x = -2; x <= 3; x++) for (int z = -2; z <= 2; z++)
                        level.setBlock(foot.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
                    var state = Blocks.BED.red().defaultBlockState().setValue(AbstractBedBlock.FACING, Direction.EAST);
                    level.setBlock(foot, state.setValue(AbstractBedBlock.PART, BedPart.FOOT), 2);
                    level.setBlock(head, state.setValue(AbstractBedBlock.PART, BedPart.HEAD), 2);
                    players.get(i).setGameMode(GameType.CREATIVE);
                    players.get(i).teleportTo(level, head.getX()+0.5, head.getY()+1.0, 0.5, Set.of(), 0, 0, false);
                }
                var type = CloudTypeDataManager.getServerInstance().getCloudTypeForId(Identifier.parse("simpleclouds:cumulonimbus"));
                require(type != null && type.weatherType().includesThunder(), "missing thunder type");
                nearby = new CloudRegion(type.id(), Vec2.ZERO, 0, 0, 0, 0, 64, 0, 1, 10000, 0, 1000);
                distant = new CloudRegion(type.id(), Vec2.ZERO, 0, 0, 1000, 1000, 16, 0, 1, 10000, 0, 1000);
                manager.getCloudGenerator().removeAllClouds();
                require(manager.getCloudGenerator().addCloud(nearby, CloudGenerator.Order.TOP), "near storm add rejected");
                require(manager.getCloudGenerator().addCloud(distant, CloudGenerator.Order.TOP), "far storm add rejected");
                stage = 1; ticks = 0;
                LOG.info("[SLEEP-PROBE] setup: two players, daytime, distinct nearby/distant thunder regions");
            } else if (stage == 1 && ticks >= 30) {
                var first = players.get(0); var head = BEDS[0];
                var state = level.getBlockState(head); var bed = (AbstractBedBlock)state.getBlock();
                require(!bed.getBedRule(level, head).canSleep(level), "fixture is not daytime");
                require(SimpleCloudsEvents.allowSleepingDuringThunderClouds(first), "no thunder over test player");
                first.teleportTo(level, head.getX()+20, head.getY()+1, 0.5, Set.of(), 0, 0, false);
                require(first.startSleepInBed(bed, state, bed.getBedRule(level, head), head).left().isPresent(), "distance restriction bypassed");
                first.teleportTo(level, head.getX()+0.5, head.getY()+1, 0.5, Set.of(), 0, 0, false);
                require(first.startSleepInBed(bed, state, BedRule.DESTROY_ON_USE, head).left().isPresent(), "forbidden bed rule bypassed");
                sleep(first, head);
                stage = 2; ticks = 0;
                LOG.info("[SLEEP-PROBE] daytime thunder sleep accepted; distance/forbidden rules retained");
            } else if (stage == 2 && ticks >= 130) {
                require(players.get(0).isSleepingLongEnough() && !players.get(1).isSleeping(), "one-player sleep fixture changed");
                require(manager.getCloudGenerator().getClouds().contains(nearby), "insufficient sleep removed storm");
                players.get(0).stopSleepInBed(true, true);
                stage = 3; ticks = 0;
                LOG.info("[SLEEP-PROBE] one deep sleeper did not clear storm at 100 percent requirement");
            } else if (stage == 3 && ticks >= 20) {
                require(manager.getCloudGenerator().getClouds().contains(nearby), "leaving bed removed storm");
                sleep(players.get(0), BEDS[0]); sleep(players.get(1), BEDS[1]);
                stage = 4; ticks = 0;
                LOG.info("[SLEEP-PROBE] leaving bed preserved storm; both players now sleeping");
            } else if (stage == 4 && !players.get(0).isSleeping() && !players.get(1).isSleeping()) {
                require(ticks >= 90, "wake before collective deep sleep");
                require(!manager.getCloudGenerator().getClouds().contains(nearby), "collective sleep failed to remove nearby storm");
                require(manager.getCloudGenerator().getClouds().contains(distant), "unrelated far storm removed");
                stage = 99;
                LOG.info("[SLEEP-PROBE] PASS: collective deep sleep removed nearby storm, preserved distant storm");
            }
        } catch (Throwable failure) {
            stage = 99;
            LOG.error("Simple Clouds ERROR: dedicated sleep fixture failed", failure);
        }
    }
}

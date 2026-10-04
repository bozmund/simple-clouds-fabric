package dev.nonamecrackers2.simpleclouds.client;

import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;
import dev.nonamecrackers2.simpleclouds.client.mesh.generator.CloudMeshGenerator;
import dev.nonamecrackers2.simpleclouds.common.world.CloudManager;
import net.minecraft.client.Minecraft;
import java.util.concurrent.CompletableFuture;

/** Actual game resource reload fixture, explicit opt-in and scratch world only. */
public final class CounterSnapshotRuntimeProbe {
    private static int ticks,reloads,settled;
    private static boolean done;
    private static CompletableFuture<Void> reload;
    private static CloudMeshGenerator previous;
    private static Object world;
    private static long seed;
    private CounterSnapshotRuntimeProbe() {}
    public static void tick(Minecraft mc) {
        if(done || !"1".equals(System.getenv("SIMPLECLOUDS_DEV"))
            || !"1".equals(System.getenv("SIMPLECLOUDS_TEST_COUNTER_SNAPSHOT"))
            || !"1".equals(System.getenv("SIMPLECLOUDS_TEST_COUNTER_RELOAD")))return;
        if(mc.level==null || mc.player==null || mc.getSingleplayerServer()==null)return;
        if(!mc.gameDirectory.toPath().toAbsolutePath().normalize().toString().equals(
            "/home/jan/.local/share/ModrinthApp/profiles/Fabric 26.3"))
            throw new IllegalStateException("Counter reload fixture requires isolated full-pack launch");
        if(!mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
            .normalize().getFileName().toString().equals("CodexFullPackTest"))
            throw new IllegalStateException("Counter reload fixture requires scratch save");
        if(++ticks>600)throw new IllegalStateException("Counter reload fixture timed out");
        var renderer=SimpleCloudsRenderer.getInstance();
        if(renderer==null)return;
        var generator=renderer.getMeshGenerator();
        if(reload!=null) {
            if(!reload.isDone())return;
            reload.join(); // Already completed; never block the render thread.
            if(previous.canRender())throw new IllegalStateException("Reload retained old generator resources");
            if(mc.level!=world || CloudManager.get(mc.level)==null || CloudManager.get(mc.level).getSeed()!=seed)
                throw new IllegalStateException("Resource reload changed world or cloud seed");
            if(generator==null || generator==previous || !generator.canRender() || generator.getCompletedGenerationCycles()<2)return;
            reload=null;settled=0;reloads++;
            org.slf4j.LoggerFactory.getLogger("simpleclouds/CounterReload").info(
                "[COUNTER-RELOAD] actual resource reload={} old generator closed; fresh generation cycles={} seed/world preserved",
                reloads,generator.getCompletedGenerationCycles());
            if(reloads==2) {
                done=true;
                org.slf4j.LoggerFactory.getLogger("simpleclouds/CounterReload").info(
                    "[COUNTER-RELOAD] PASS two actual resource reloads and subsequent snapshot publication");
            }
            return;
        }
        if(generator==null || !generator.canRender() || generator.getCompletedGenerationCycles()<2)return;
        if(++settled<80)return;
        previous=generator;world=mc.level;seed=CloudManager.get(mc.level).getSeed();
        reload=mc.reloadResourcePacks();
    }
}

package dev.nonamecrackers2.simpleclouds.client;

import java.util.Objects;
import dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.server.permissions.PermissionSet;

/** Actual integrated-server dispatcher exercise, explicitly opt-in scratch only. */
final class ServerConfigCommandProbe {
    private static boolean queued;
    private static int exitStage;
    private static volatile dev.nonamecrackers2.simpleclouds.common.config.ServerConfigSnapshot expectedConfig;
    private static int syncStage;
    private static int syncWaitTicks;
    static void finish() {
        if(queued) {
            if(syncStage!=5) LOG.error("Simple Clouds ERROR: server config sync probe did not finish, stage={}",syncStage);
            exitStage=1;
        }
    }
    private static final org.apache.logging.log4j.Logger LOG=org.apache.logging.log4j.LogManager.getLogger("simpleclouds/ConfigCommandProbe");
    private static void require(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    static void tick(Minecraft mc) {
        if(queued || !"1".equals(System.getenv("SIMPLECLOUDS_DEV"))
                || !"1".equals(System.getenv("SIMPLECLOUDS_TEST_CONFIG_COMMANDS"))) return;
        var server=mc.getSingleplayerServer();
        if(server==null || !server.getWorldData().getLevelName().equals("CodexConfigCommands20261001")) return;
        queued=true;
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            verifySync(client);
            if(exitStage==1) {
                exitStage=2;
                client.disconnectWithSavingScreen();
            }
            if(exitStage==2 && !SimpleCloudsConfig.SERVER_SPEC.isLoaded()) {
                require(dev.nonamecrackers2.simpleclouds.client.config.ClientServerConfig.get()==null,"server snapshot leaked after disconnect");
                exitStage=0;
                LOG.info("[SERVER-CONFIG-COMMANDS] saved world exit verified: server spec unloaded");
            }
        });
        server.execute(() -> {
            var config=SimpleCloudsConfig.SERVER.whitelistAsBlacklist;
            var interval=SimpleCloudsConfig.COMMON.lightningSpawnIntervalMin;
            boolean old=config.get(); int oldInterval=interval.get();
            try {
                var source=server.createCommandSourceStack().withSuppressedOutput();
                var dispatcher=server.getCommands().getDispatcher();
                String root="simpleclouds config server ";
                String path=String.join(".",config.getPath());
                String commonPath=String.join(".",interval.getPath());
                require(dispatcher.execute(root+"get "+path,source)==(old?1:0),"get boolean result");
                require(dispatcher.execute(root+"set "+path+" "+!old,source)==2,"restart-required result");
                require(config.get()!=old,"boolean mutation");
                try(var file=com.electronwill.nightconfig.core.file.CommentedFileConfig.of(
                        dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfigLoader.serverConfigPath())) {
                    file.load(); require(Objects.equals(file.get(config.getPath()),!old),"disk persistence");
                }
                try { dispatcher.execute(root+"set "+path+" invalid",source); throw new AssertionError("invalid boolean accepted"); }
                catch(com.mojang.brigadier.exceptions.CommandSyntaxException expected) {}
                require(config.get()!=old,"invalid command mutated config");
                try { dispatcher.execute(root+"set "+path+" "+old,source.withPermission(PermissionSet.NO_PERMISSIONS));
                    throw new AssertionError("unprivileged write accepted"); }
                catch(com.mojang.brigadier.exceptions.CommandSyntaxException expected) {}
                require(config.get()!=old,"permission failure mutated config");
                int defaultResult=config.getDefault()!=config.get()?2:0;
                require(dispatcher.execute(root+"set "+path+" default",source)==defaultResult,"default result");
                require(config.get().equals(config.getDefault()),"default not restored");
                String common="simpleclouds config common set "+commonPath+" ";
                int next=oldInterval==11 ? 12 : 11;
                require(dispatcher.execute(common+next,source)==1 && interval.get()==next,"common integer mutation");
                try { dispatcher.execute(common+"0",source); throw new AssertionError("out-of-range accepted"); }
                catch(com.mojang.brigadier.exceptions.CommandSyntaxException expected) {}
                require(interval.get()==next,"invalid range mutated config");
                require(dispatcher.execute("simpleclouds config server get cloudMode",source)==SimpleCloudsConfig.SERVER.cloudMode.get().ordinal(),"enum get");
                try { dispatcher.execute("simpleclouds config server set cloudMode invalid",source); throw new AssertionError("invalid enum accepted"); }
                catch(com.mojang.brigadier.exceptions.CommandSyntaxException expected) {}
                try { dispatcher.execute(root+"get nonexistent.path",source); throw new AssertionError("unknown option accepted"); }
                catch(com.mojang.brigadier.exceptions.CommandSyntaxException expected) {}
                LOG.info("[SERVER-CONFIG-COMMANDS] actual dispatcher verified: get/set/default, restart result, disk persistence, permissions, invalid type/range/enum/path");
            } catch(Throwable error) { LOG.error("Simple Clouds ERROR: actual server config command probe failed",error); }
            finally {
                config.set(old); interval.set(oldInterval);
                config.save(); interval.save();
                expectedConfig=dev.nonamecrackers2.simpleclouds.common.config.ServerConfigSnapshot.capture();
                LOG.info("[SERVER-CONFIG-COMMANDS] original values restored boolean={} integer={}",old,oldInterval);
            }
        });
    }

    private static void verifySync(Minecraft client) {
        if(expectedConfig==null || syncStage>=5 || exitStage!=0) return;
        var received=dev.nonamecrackers2.simpleclouds.client.config.ClientServerConfig.get();
        var server=client.getSingleplayerServer();
        if(server==null) return;
        if(++syncWaitTicks>600) {
            LOG.error("Simple Clouds ERROR: timed out waiting for server config sync stage={} received={}",syncStage,received);
            syncStage=6;
            return;
        }
        if(syncStage==0 && expectedConfig.equals(received)) {
            LOG.info("[SERVER-CONFIG-SYNC] initial four-field snapshot verified");
            syncStage=1; syncWaitTicks=0;
            server.execute(() -> {
                SimpleCloudsConfig.SERVER.whitelistAsBlacklist.set(!expectedConfig.whitelistAsBlacklist());
                SimpleCloudsConfig.SERVER.whitelistAsBlacklist.save();
            });
        } else if(syncStage==1 && received!=null
                && received.equals(new dev.nonamecrackers2.simpleclouds.common.config.ServerConfigSnapshot(
                    expectedConfig.cloudMode(),expectedConfig.singleModeCloudType(),expectedConfig.dimensionWhitelist(),!expectedConfig.whitelistAsBlacklist()))) {
            LOG.info("[SERVER-CONFIG-SYNC] changed blacklist snapshot verified over connection");
            syncStage=2; syncWaitTicks=0;
            server.execute(() -> {
                SimpleCloudsConfig.SERVER.whitelistAsBlacklist.set(expectedConfig.whitelistAsBlacklist());
                SimpleCloudsConfig.SERVER.whitelistAsBlacklist.save();
            });
        } else if(syncStage==2 && expectedConfig.equals(received)) {
            syncStage=3;
            LOG.info("[SERVER-CONFIG-SYNC] restored snapshot verified over connection");
            server.execute(() -> editDisk(!expectedConfig.whitelistAsBlacklist()));
        } else if(syncStage==3 && received!=null && received.equals(
                new dev.nonamecrackers2.simpleclouds.common.config.ServerConfigSnapshot(expectedConfig.cloudMode(),
                    expectedConfig.singleModeCloudType(),expectedConfig.dimensionWhitelist(),!expectedConfig.whitelistAsBlacklist()))) {
            syncStage=4; syncWaitTicks=0;
            LOG.info("[SERVER-CONFIG-RELOAD] external file edit reloaded and synchronized");
            server.execute(() -> editDisk(expectedConfig.whitelistAsBlacklist()));
        } else if(syncStage==4 && expectedConfig.equals(received)) {
            syncStage=5;
            LOG.info("[SERVER-CONFIG-RELOAD] external file restoration reloaded and synchronized");
        }
    }

    private static void editDisk(boolean value) {
        var option=SimpleCloudsConfig.SERVER.whitelistAsBlacklist;
        boolean liveBefore=option.get();
        try(var file=com.electronwill.nightconfig.core.file.CommentedFileConfig.builder(
                dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfigLoader.serverConfigPath()).sync()
                .writingMode(com.electronwill.nightconfig.core.io.WritingMode.REPLACE_ATOMIC).build()) {
            file.load(); file.set(option.getPath(),value); file.save();
            require(option.get()==liveBefore,"external edit bypassed loader/cache boundary");
        } catch(Throwable error) {
            LOG.error("Simple Clouds ERROR: external config file probe failed",error);
        }
    }
}

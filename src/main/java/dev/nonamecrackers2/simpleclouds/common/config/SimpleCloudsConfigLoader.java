package dev.nonamecrackers2.simpleclouds.common.config;

import java.nio.file.Path;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import com.electronwill.nightconfig.toml.TomlFormat;

import dev.nonamecrackers2.simpleclouds.SimpleCloudsMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Fabric port of the config loading that Forge's ModConfig system used to do
	 * implicitly: CLIENT/COMMON use config/, SERVER uses the world's serverconfig/.
 *
 * On Fabric we must do it ourselves: {@link ForgeConfigSpec#setConfig} binds the
 * loaded night-config to the spec. Without this, {@code isLoaded()} stays false
 * forever and {@code ConfigValue.get()} throws in dev environments (and silently
 * returns defaults in production), which e.g. breaks player join
 * (CloudManager.onPlayerJoin → getCloudMode).
 */
public class SimpleCloudsConfigLoader
{
	private static final Logger LOGGER = LogManager.getLogger("simpleclouds/ConfigLoader");
	private static WorldConfigBinding serverBinding;
	private static String lastReloadError;

	/** Server + integrated server: server and common configs. */
	public static synchronized void loadServerConfigs(net.minecraft.server.MinecraftServer server)
	{
		String name=SimpleCloudsMod.MODID + "-server.toml";
		Path target=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("serverconfig").resolve(name);
		Path template=FabricLoader.getInstance().getGameDir().resolve("defaultconfigs").resolve(name);
		// One-time migration seed only; never move or overwrite legacy user data.
		if(!java.nio.file.Files.isRegularFile(template)) template=FabricLoader.getInstance().getConfigDir().resolve(name);
		if(serverBinding==null) serverBinding=new WorldConfigBinding(SimpleCloudsConfig.SERVER_SPEC);
		try {
			serverBinding.open(target,template);
			LOGGER.info("Loaded world server config {}",serverBinding.path());
		} catch(java.io.IOException | RuntimeException error) {
			throw new IllegalStateException("Cannot load world server config " + target,error);
		}
		loadSpec(SimpleCloudsMod.MODID + "-common.toml", SimpleCloudsConfig.COMMON_SPEC);
	}
	public static synchronized Path serverConfigPath() { return serverBinding==null ? null : serverBinding.path(); }
	public static synchronized void unloadServerConfig() {
		lastReloadError=null;
		if(serverBinding!=null) {
			Path previous=serverBinding.path();
			serverBinding.close();
			LOGGER.info("Unloaded world server config {} loaded={}",previous,SimpleCloudsConfig.SERVER_SPEC.isLoaded());
		}
	}

	/** Poll only the active world's file; all binding/cache updates stay on its server thread. */
	public static synchronized void tickServerConfig(net.minecraft.server.MinecraftServer server) {
		if(serverBinding==null || server.getTickCount()%20!=0) return;
		try {
			if(serverBinding.reloadIfChanged()) {
				LOGGER.info("Reloaded world server config {}",serverBinding.path());
				lastReloadError=null;
			}
		} catch(java.io.IOException | RuntimeException error) {
			String message=error.getClass().getName()+": "+error.getMessage();
			if(!message.equals(lastReloadError))
				LOGGER.error("Rejected server config reload {}; previous valid values retained",serverBinding.path(),error);
			lastReloadError=message;
		}
	}

	/** Client (and integrated client): client and common configs. */
	public static void loadClientConfigs()
	{
		loadSpec(SimpleCloudsMod.MODID + "-client.toml", SimpleCloudsConfig.CLIENT_SPEC);
		loadSpec(SimpleCloudsMod.MODID + "-common.toml", SimpleCloudsConfig.COMMON_SPEC);
	}

	private static void loadSpec(String fileName, ForgeConfigSpec spec)
	{
		if (spec.isLoaded())
			return;
		try
		{
			Path path = FabricLoader.getInstance().getConfigDir().resolve(fileName);
			CommentedFileConfig config = CommentedFileConfig.builder(path, TomlFormat.instance())
					.sync()
					.writingMode(com.electronwill.nightconfig.core.io.WritingMode.REPLACE_ATOMIC)
					.onFileNotFound(FileNotFoundAction.CREATE_EMPTY)
					.build();
			config.load();
			spec.setConfig(config);
			LOGGER.info("Loaded config {}", fileName);
		}
		catch (Throwable t)
		{
			LOGGER.error("Failed to load config {}", fileName, t);
		}
	}
}

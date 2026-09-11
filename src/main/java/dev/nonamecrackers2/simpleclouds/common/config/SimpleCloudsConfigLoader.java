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
 * implicitly (registering a spec via {@code addSpec} made Forge load it from
 * {@code config/<modid>-<type>.toml} and bind it to the spec).
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

	/** Server + integrated server: server and common configs. */
	public static void loadServerConfigs()
	{
		loadSpec(SimpleCloudsMod.MODID + "-server.toml", SimpleCloudsConfig.SERVER_SPEC);
		loadSpec(SimpleCloudsMod.MODID + "-common.toml", SimpleCloudsConfig.COMMON_SPEC);
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

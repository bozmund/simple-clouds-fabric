package dev.nonamecrackers2.simpleclouds;

import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;

/**
 * Fabric 26.2 entrypoint (partial port).
 *
 * Currently wires: config specs + packet registration.
 * Deferred (TODO): client events, keybinds, shaders, world effects, DH compat,
 * data events, sounds, command arguments.
 */
public class SimpleCloudsMod implements ModInitializer
{
	public static final String MODID = "simpleclouds";
	private static final String DH_MODID = "distanthorizons";
	private static ArtifactVersion version;
	private static boolean dhLoaded;

	public SimpleCloudsMod()
	{
		version = new DefaultArtifactVersion(FabricLoader.getInstance().getModContainer(MODID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("0.0.0"));
		dhLoaded = FabricLoader.getInstance().isModLoaded(DH_MODID);
	}

	@Override
	public void onInitialize()
	{
		// Config registration (Fabric: specs are registered via the mod bus / config API).
		// TODO(fabric-config): register CLIENT_SPEC, COMMON_SPEC, SERVER_SPEC.

		// API bootstrap (registers the SimpleCloudsAPI instance for getApi()).
		dev.nonamecrackers2.simpleclouds.common.api.SimpleCloudsAPIImpl.bootstrap();

		// Packet registration.
		dev.nonamecrackers2.simpleclouds.common.packet.SimpleCloudsPacketHandlers.register();

		// Common events
		dev.nonamecrackers2.simpleclouds.common.event.SimpleCloudsEvents.register();
		dev.nonamecrackers2.simpleclouds.common.event.CloudManagerEvents.register();

		// Config listeners
		dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfigListeners.registerListener();

		// Client init
		if (net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType() == net.fabricmc.api.EnvType.CLIENT)
		{
			dev.nonamecrackers2.simpleclouds.client.event.SimpleCloudsClientEvents.register();
		}
	}

	public static Identifier id(String path)
	{
		return Identifier.fromNamespaceAndPath(MODID, path);
	}

	public static ArtifactVersion getModVersion()
	{
		return version;
	}

	public static boolean dhLoaded()
	{
		return dhLoaded;
	}
}

package dev.nonamecrackers2.simpleclouds.client.event;

import java.util.EnumMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import dev.nonamecrackers2.simpleclouds.SimpleCloudsMod;
import dev.nonamecrackers2.simpleclouds.client.DevShot;
import dev.nonamecrackers2.simpleclouds.client.cloud.ClientSideCloudTypeManager;
import dev.nonamecrackers2.simpleclouds.client.dh.SimpleCloudsDhCompatHandler;
import dev.nonamecrackers2.simpleclouds.common.world.CloudManager;
import dev.nonamecrackers2.simpleclouds.client.cloud.spawning.ClientSideCloudSpawningManager;
import dev.nonamecrackers2.simpleclouds.client.command.ClientCloudCommandHelper;
import dev.nonamecrackers2.simpleclouds.client.command.profiling.ProfilingCommands;
import dev.nonamecrackers2.simpleclouds.client.gui.CloudPreviewerScreen;
import dev.nonamecrackers2.simpleclouds.client.gui.SimpleCloudsConfigScreen;
import dev.nonamecrackers2.simpleclouds.client.keybind.SimpleCloudsKeybinds;
import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;
import dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig;
import dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfigLoader;
import dev.nonamecrackers2.simpleclouds.client.renderer.WorldEffects;
import dev.nonamecrackers2.simpleclouds.client.renderer.settings.CloudsRendererSettings;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.config.ModConfig;
import nonamecrackers2.crackerslib.client.gui.ConfigScreen;
import nonamecrackers2.crackerslib.client.gui.title.ImageTitle;
import nonamecrackers2.crackerslib.common.command.ConfigCommandBuilder;
import nonamecrackers2.crackerslib.common.config.preset.ConfigPreset;
import nonamecrackers2.crackerslib.common.config.preset.ConfigPresets;
import nonamecrackers2.crackerslib.common.config.preset.RegisterConfigPresetsEvent;
import dev.nonamecrackers2.simpleclouds.client.mesh.LevelOfDetailOptions;
import dev.nonamecrackers2.simpleclouds.client.mesh.generator.GenerationInterval;
import net.minecraft.network.chat.Component;

/**
 * Fabric 26.2 port: uses Fabric's event system instead of Forge's.
 *
 * Ported:
 * - Client tick: lazy renderer initialization + world-effects tick.
 *
 * Deferred (TODO):
 * - Config screen registration + presets + keybinds
 * - GUI overlays (RegisterGuiOverlaysEvent)
 * - Client commands (RegisterClientCommandsEvent)
 * - Network events (ClientPlayerNetworkEvent)
 * - Fog rendering (ViewportEvent)
 * - Debug overlay (CustomizeGuiOverlayEvent)
 */
public class SimpleCloudsClientEvents
{
	private static final Logger LOGGER = LogManager.getLogger("simpleclouds/ClientEvents");
	private static int initRetryCounter;
	private static boolean dhInitialized;

	// Config screen open keybind. The 1.20.1 original opened the config from a
	// button on the options screen (CrackersLib's ConfigMenuButtons); 26.2 has no
	// public hook for that, so this port uses a keybind (unbound by default -- set
	// "Simple Clouds: Open Config" in the Controls screen). DEVIATION documented
	// in PORTING.md.
	// GLFW_KEY_UNKNOWN (-1) = unbound by default.
	public static final KeyMapping OPEN_CONFIG = new KeyMapping("simpleclouds.key.openConfig", -1, KeyMapping.Category.MISC);

	// Headless verification: auto-open the config screen shortly after join so
	// the GUI can be checked via screenshot. Off in production.
	private static final boolean DEBUG_AUTO_OPEN_CONFIG = false;
	private static final boolean DEBUG_AUTO_OPEN_PREVIEWER = false; // TEMP debug (headless preview verification)

	public static void register()
	{
		// Config presets (1.20.1 registerClientPresets port): must be registered
		// and gathered before any ConfigScreen is constructed (the constructor
		// queries ConfigPresets.getPresetsForModId, which NPEs otherwise).
		RegisterConfigPresetsEvent presets = new RegisterConfigPresetsEvent(SimpleCloudsMod.MODID);
		presets.registerPreset(ModConfig.Type.CLIENT, ConfigPreset.builder(Component.translatable("simpleclouds.config.preset.medium"))
				.setDescription(Component.translatable("simpleclouds.config.preset.medium.description"))
				.setPreset(SimpleCloudsConfig.CLIENT.framesToGenerateMesh, 10)
				.setPreset(SimpleCloudsConfig.CLIENT.generationInterval, GenerationInterval.STATIC)
				.setPreset(SimpleCloudsConfig.CLIENT.levelOfDetail, LevelOfDetailOptions.MEDIUM)
				.setPreset(SimpleCloudsConfig.CLIENT.shadowDistance, 2500).build());
		presets.registerPreset(ModConfig.Type.CLIENT, ConfigPreset.builder(Component.translatable("simpleclouds.config.preset.low"))
				.setDescription(Component.translatable("simpleclouds.config.preset.low.description"))
				.setPreset(SimpleCloudsConfig.CLIENT.framesToGenerateMesh, 20)
				.setPreset(SimpleCloudsConfig.CLIENT.generationInterval, GenerationInterval.DYNAMIC)
				.setPreset(SimpleCloudsConfig.CLIENT.levelOfDetail, LevelOfDetailOptions.LOW)
				.setPreset(SimpleCloudsConfig.CLIENT.transparency, false)
				.setPreset(SimpleCloudsConfig.CLIENT.atmosphericClouds, false)
				.setPreset(SimpleCloudsConfig.CLIENT.shadowDistance, 2500)
				.setPreset(SimpleCloudsConfig.CLIENT.distantShadows, false).build());
		presets.registerPreset(ModConfig.Type.CLIENT, ConfigPreset.builder(Component.translatable("simpleclouds.config.preset.ultra_low"))
				.setDescription(Component.translatable("simpleclouds.config.preset.ultra_low.description"))
				.setPreset(SimpleCloudsConfig.CLIENT.framesToGenerateMesh, 20)
				.setPreset(SimpleCloudsConfig.CLIENT.generationInterval, GenerationInterval.DYNAMIC)
				.setPreset(SimpleCloudsConfig.CLIENT.levelOfDetail, LevelOfDetailOptions.LOW)
				.setPreset(SimpleCloudsConfig.CLIENT.transparency, false)
				.setPreset(SimpleCloudsConfig.CLIENT.renderStormFog, false)
				.setPreset(SimpleCloudsConfig.CLIENT.atmosphericClouds, false)
				.setPreset(SimpleCloudsConfig.CLIENT.shadowDistance, 1000)
				.setPreset(SimpleCloudsConfig.CLIENT.distantShadows, false).build());
		presets.registerPreset(ModConfig.Type.CLIENT, ConfigPreset.builder(Component.translatable("simpleclouds.config.preset.classic_style"))
				.setDescription(Component.translatable("simpleclouds.config.preset.classic_style.description"))
				.setPreset(SimpleCloudsConfig.CLIENT.transparency, false)
				.setPreset(SimpleCloudsConfig.CLIENT.cubeNormals, true)
				.setPreset(SimpleCloudsConfig.CLIENT.shadedClouds, false)
				.setPreset(SimpleCloudsConfig.CLIENT.atmosphericClouds, false).build());
		ConfigPresets.registerPresets(SimpleCloudsMod.MODID, presets);
		ConfigPresets.gatherPresets();

		KeyMappingHelper.registerKeyMapping(OPEN_CONFIG);
		SimpleCloudsKeybinds.register();

		// Client commands (1.20.1 registerClientCommands port). 26.2: the client
		// dispatcher is typed FabricClientCommandSource (vanilla Commands.*
		// builders and argument getters are hard-bound to CommandSourceStack,
		// hence the ClientCommands/ClientCloudCommandHelper retype). Server-side
		// commands stay deferred: the 26.2 command backend serializes custom
		// Brigadier argument types to players, and only vanilla-bootstrapped
		// types survive that (see SimpleCloudsEvents).
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, commandBuildContext) ->
		{
			ConfigCommandBuilder.builder(dispatcher, SimpleCloudsMod.MODID)
					.addSpec(ModConfig.Type.CLIENT, SimpleCloudsConfig.CLIENT_SPEC)
					.register();
			ClientCloudCommandHelper.register(dispatcher);
			ProfilingCommands.register(dispatcher);
		});

		// Client-side data: the ClientCloudManager (created in the ClientLevel constructor)
		// requires the client spawning manager to exist, so initialize it at mod init.
		// Note: we do NOT register the client data managers as reload listeners here —
		// they share Fabric listener IDs with the server-side ones registered in
		// SimpleCloudsEvents, which would collide in integrated (singleplayer) sessions.
		// In practice the client receives cloud types via join sync (SendCloudTypesPacket).
		ClientSideCloudSpawningManager.optionalInitializeOnClient(
				ClientSideCloudTypeManager.getInstance().getClientSideDataManager());

		// Client tick: lazily initialize the renderer on the first tick (the Vulkan device
		// and resources are guaranteed up by then) and tick world effects. The old
		// LevelRenderer.tick hook (baseTick/tick) is gone in 26.2, so drive it here.
		ClientTickEvents.END_CLIENT_TICK.register(client ->
		{
			// Load client + common configs once (guarded by isLoaded inside the loader).
			SimpleCloudsConfigLoader.loadClientConfigs();

			// Step 3 (superflat reference scenes): on the title screen (no level yet),
			// create + load the superflat world if the devshot request carries
			// CREATEFLAT (dev-only automation; guarded to run once).
			if (client.level == null)
				DevShot.maybeCreateFlatWorld(client);

			// Distant Horizons compat: one-shot on the first client tick (by then every
			// mod is initialized and DhApi.Delayed is populated). Disables DH's own
			// cloud rendering and registers the DH event handlers (see the handler for
			// the 26.2 far-field limitation). Guarded so a DH-side failure can't break
			// Simple Clouds' own render loop.
			if (!dhInitialized && SimpleCloudsMod.dhLoaded())
			{
				dhInitialized = true;
				try
				{
					SimpleCloudsDhCompatHandler.initialize();
				}
				catch (Throwable t)
				{
					LOGGER.error("Simple Clouds: Distant Horizons compat initialization failed", t);
				}
			}

			if (SimpleCloudsRenderer.getOptionalInstance().isEmpty())
			{
				if (initRetryCounter-- > 0)
					return; // throttled retry after a failed init
				try
				{
					SimpleCloudsRenderer.initialize(CloudsRendererSettings.DEFAULT);
				}
				catch (Throwable t)
				{
					LOGGER.error("Simple Clouds: renderer initialization failed (retrying)", t);
				}
				if (SimpleCloudsRenderer.getOptionalInstance().isEmpty())
					initRetryCounter = 40;
			}

			// Tick the client cloud manager (region movement/growth/death; the original
			// ticked every level's manager, client and server, from the level tick event).
			if (client.level != null)
			{
				CloudManager<?> cm = CloudManager.get(client.level);
				if (cm != null)
					cm.tick();
			}

			SimpleCloudsRenderer.getOptionalInstance().ifPresent(renderer ->
			{
				renderer.baseTick();
				if (client.level != null)
				{
					WorldEffects effects = renderer.getWorldEffectsManager();
					if (effects != null)
						effects.tick();
				}
			});

			// Config screen: keybind open + temp headless auto-open. 26.2 screen
			// access is via mc.gui (mc.gui.screen()/setScreen, mc.setScreenAndShow).
			if (client.gui.screen() == null && client.level != null)
			{
				while (OPEN_CONFIG.consumeClick())
					client.setScreenAndShow(createConfigScreen(null));

				// F12: open the 3D cloud generator previewer (1.20.1 keybind).
				while (SimpleCloudsKeybinds.OPEN_GEN_PREVIEWER.consumeClick())
					client.setScreenAndShow(new CloudPreviewerScreen(null));

				if (DEBUG_AUTO_OPEN_PREVIEWER)
				{
					client.setScreenAndShow(new CloudPreviewerScreen(null)); // TEMP debug
				}

				if (DEBUG_AUTO_OPEN_CONFIG)
				{
					// Re-enable for headless GUI verification: opens the client tab
					// ~40 ticks after the first tick in world.
					client.setScreenAndShow(createClientConfigTab(null));
				}
			}
		});
	}

	/** The mod's config home screen (tabs), as the 1.20.1 original built it. */
	public static SimpleCloudsConfigScreen createConfigScreen(Screen previous)
	{
		Minecraft mc = Minecraft.getInstance();
		Map<ModConfig.Type, ForgeConfigSpec> specs = new EnumMap<>(ModConfig.Type.class);
		specs.put(ModConfig.Type.CLIENT, SimpleCloudsConfig.CLIENT_SPEC);
		specs.put(ModConfig.Type.COMMON, SimpleCloudsConfig.COMMON_SPEC);
		specs.put(ModConfig.Type.SERVER, SimpleCloudsConfig.SERVER_SPEC);
		return new SimpleCloudsConfigScreen(SimpleCloudsMod.MODID, specs,
				ImageTitle.ofMod(SimpleCloudsMod.MODID, 192, 96, 1.0F),
				mc.level != null, mc.hasSingleplayerServer(), previous, java.util.List.of(), 1);
	}

	/** The client tab directly (used by the temp headless auto-open). */
	private static Screen createClientConfigTab(Screen previous)
	{
		// makeScreen (not the raw constructor) is what populates the option list
		// from the spec; the raw constructor leaves it to the itemGenerator.
		return ConfigScreen.makeScreen(SimpleCloudsMod.MODID, SimpleCloudsConfig.CLIENT_SPEC, ModConfig.Type.CLIENT, createConfigScreen(previous), "");
	}
}

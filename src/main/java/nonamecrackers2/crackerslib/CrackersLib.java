package nonamecrackers2.crackerslib;

import net.minecraft.resources.Identifier;

/**
 * Fabric port: CrackersLib is now a library bundled inside the simpleclouds mod
 * (not a separate @Mod). SimpleCloudsMod is the single Fabric entrypoint and calls
 * {@link #onInitialize()} / {@link #onClientInitialize()} during setup.
 */
public class CrackersLib
{
	public static final String MODID = "crackerslib";

	/**
	 * Common-side init. Called from the host mod's main entrypoint.
	 */
	public static void onInitialize()
	{
		// Config spec is already built eagerly in CrackersLibConfig's static block.
		// Presets are gathered by the host mod after it registers its own.
	}

	/**
	 * Client-side init. Called from the host mod's client entrypoint.
	 */
	public static void onClientInitialize()
	{
		// Config screens / menu buttons are registered by the host mod.
	}

	public static Identifier id(String path)
	{
		return Identifier.fromNamespaceAndPath(MODID, path);
	}
}

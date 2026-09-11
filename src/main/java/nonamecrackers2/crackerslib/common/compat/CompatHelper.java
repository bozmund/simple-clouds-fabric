package nonamecrackers2.crackerslib.common.compat;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.collect.Lists;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Fabric port: uses FabricLoader instead of Forge's ModList for mod detection.
 */
public class CompatHelper
{
	private static final Logger LOGGER = LogManager.getLogger("crackerslib/CompatHelper");
	private static final List<String> COMPAT_ERRORS = Lists.newArrayList();
	private static boolean optifineLoaded;
	private static boolean vivecraftStandaloneLoaded;

	public static void checkForLoaded()
	{
		try
		{
			Class.forName("net.optifine.Config");
			optifineLoaded = true;
		}
		catch (ClassNotFoundException e)
		{
		}

		try
		{
			Class.forName("org.vivecraft.settings.VRSettings");
			vivecraftStandaloneLoaded = true;
		}
		catch (ClassNotFoundException e)
		{
		}
	}

	public static boolean areShadersRunning()
	{
		try
		{
			if (isOculusLoaded())
			{
				var clazz = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
				var instanceGetter = clazz.getMethod("getInstance");
				var irisApi = instanceGetter.invoke(null);
				return (boolean)irisApi.getClass().getMethod("isShaderPackInUse").invoke(irisApi);
			}
			else if (isOptifineLoaded())
			{
				var clazz = Class.forName("net.optifine.Config");
				var method = clazz.getMethod("isShaders");
				return (boolean)method.invoke(null);
			}
			else
			{
				return false;
			}
		}
		catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException | ClassNotFoundException | NoSuchMethodException | SecurityException e)
		{
			doErrorFor("shaders", () -> {
				LOGGER.error("Failed to check if shaders are enabled:");
				e.printStackTrace();
			});
			return false;
		}
	}

	public static boolean isVrActive()
	{
		if (isModLoaded("vivecraft"))
		{
			try
			{
				var clazz = Class.forName("org.vivecraft.api_beta.client.VivecraftClientAPI");
				var instanceGetter = clazz.getMethod("getInstance");
				var vivecraftApi = instanceGetter.invoke(null);
				return (boolean)vivecraftApi.getClass().getMethod("isVrActive").invoke(vivecraftApi);
			}
			catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException | ClassNotFoundException | NoSuchMethodException | SecurityException e)
			{
				doErrorFor("vr", () -> {
					LOGGER.error("Failed to check if VR is active:");
					e.printStackTrace();
				});
				return false;
			}
		}
		return vivecraftStandaloneLoaded;
	}

	public static boolean isVivecraftLoaded()
	{
		return vivecraftStandaloneLoaded || isModLoaded("vivecraft");
	}

	public static boolean isOculusLoaded()
	{
		return isModLoaded("oculus");
	}

	public static boolean isOptifineLoaded()
	{
		return optifineLoaded;
	}

	public static boolean isSodiumLoaded()
	{
		return isModLoaded("rubidium");
	}

	private static boolean isModLoaded(String modId)
	{
		return FabricLoader.getInstance().isModLoaded(modId);
	}

	private static final void doErrorFor(String mod, Runnable runnable)
	{
		if (!COMPAT_ERRORS.contains(mod))
		{
			runnable.run();
			COMPAT_ERRORS.add(mod);
		}
	}
}

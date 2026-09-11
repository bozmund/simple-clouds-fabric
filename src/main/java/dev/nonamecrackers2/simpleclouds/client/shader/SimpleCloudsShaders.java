package dev.nonamecrackers2.simpleclouds.client.shader;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.renderer.ShaderInstance;
import net.minecraftforge.client.event.RegisterShadersEvent;

/**
 * 26.2 vertical-slice stub. The legacy SSBO/ShaderInstance core shaders are replaced by
 * the new GPU pipeline (see client.renderer.v2). This keeps the type + getters so
 * dependent code compiles; the getters return null until the full renderer is ported.
 */
public class SimpleCloudsShaders
{
	private static boolean shadersInitialized;
	private static @Nullable Throwable error;

	public static void registerShaders(RegisterShadersEvent event)
	{
		// TODO(26.2): the legacy shader registration is replaced by the new pipeline.
		shadersInitialized = false;
		error = null;
	}

	public static boolean areShadersInitialized()
	{
		return shadersInitialized;
	}

	public static @Nullable Throwable getError()
	{
		return error;
	}

	public static @Nullable SingleSSBOShaderInstance getCloudsShader()
	{
		return null;
	}

	public static @Nullable SingleSSBOShaderInstance getCloudsTransparencyShader()
	{
		return null;
	}

	public static @Nullable SingleSSBOShaderInstance getStormFogShadowMapShader()
	{
		return null;
	}

	public static @Nullable SingleSSBOShaderInstance getCloudsShadowMapShader()
	{
		return null;
	}

	public static @Nullable ShaderInstance getCloudRegionTexShader()
	{
		return null;
	}
}

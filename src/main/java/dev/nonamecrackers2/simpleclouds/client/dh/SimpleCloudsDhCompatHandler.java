package dev.nonamecrackers2.simpleclouds.client.dh;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiAfterRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeApplyShaderRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderPassEvent;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;

import dev.nonamecrackers2.simpleclouds.client.dh.event.SimpleCloudsAfterDhRenderHandler;
import dev.nonamecrackers2.simpleclouds.client.dh.event.SimpleCloudsBeforeDhRenderHandler;
import dev.nonamecrackers2.simpleclouds.client.dh.event.SimpleCloudsDhSetupHandler;

/**
 * 26.2 Distant Horizons compat. The 3.2.0 DH API (com.seibel.distanthorizons.api.*)
 * is layout-identical to the 1.20.1 one the original was built against, so the
 * handlers and the cloud-disable config hook port directly.
 *
 * 26.2 DEVIATION (documented in PORTING.md): the 1.20.1 pipeline re-rendered the
 * clouds into DH's own framebuffer during DH's render pass (far-field LOD depth
 * integration). The v2 26.2 renderer draws its finite, camera-following cloud field
 * into the MAIN framebuffer during the world phase, and the 26.2 RenderPass API
 * cannot target DH's raw GL framebuffer (the API only exposes the FBO id, not the
 * GpuTextureView attachments). So in 26.2 the support reduces to: (1) disable DH's
 * OWN cloud rendering so clouds are not drawn twice, and (2) register the DH event
 * handlers (defensive state caching only -- the per-pass cloud draws are no-ops in
 * the v2 world). Far-field LOD depth accuracy is a known limitation.
 */
public class SimpleCloudsDhCompatHandler
{
	private static final Logger LOGGER = LogManager.getLogger("simpleclouds/SimpleCloudsDhCompatHandler");
	//Cached DH stuff, internal use
	private static Matrix4f dhProjMat;
	private static Matrix4f dhModelViewMat;
	private static int dhFramebufferId;
	
	private static boolean passComplete;
	
	public static void _updateCachedDhState(Matrix4f projMat, Matrix4f modelViewMat)
	{
		dhProjMat = projMat;
		dhModelViewMat = modelViewMat;
	}
	
	public static void _updateDhFramebufferId(int id)
	{
		dhFramebufferId = id;
	}
	
	public static void _markPassComplete(boolean flag)
	{
		passComplete = flag;
	}
	
	public static boolean _isPassComplete()
	{
		return passComplete;
	}
	
	/** @nullable -- null until the first DH render pass has cached it. */
	public static Matrix4f _getDhProjMat()
	{
		return dhProjMat;
	}
	
	/** @nullable -- null until the first DH render pass has cached it. */
	public static Matrix4f _getDhModelViewMat()
	{
		return dhModelViewMat;
	}
	
	/** 0 until the first DH render pass has cached the bound framebuffer. */
	public static int _getDhFramebufferId()
	{
		return dhFramebufferId;
	}
	
	public static void initialize()
	{
		LOGGER.info("Distant Horizons detected -- registering Simple Clouds compat");
		
		DhApiEventRegister.on(DhApiBeforeApplyShaderRenderEvent.class, new SimpleCloudsBeforeDhRenderHandler());
		DhApiEventRegister.on(DhApiAfterRenderEvent.class, new SimpleCloudsAfterDhRenderHandler());
		DhApiEventRegister.on(DhApiBeforeRenderPassEvent.class, new SimpleCloudsDhSetupHandler());
		
		// Simple Clouds replaces the clouds, so DH must not draw its own (the 1.20.1
		// original did exactly this); the change-listener re-asserts it if DH's own
		// config handling tries to turn it back on.
		IDhApiConfigValue<Boolean> val = DhApi.Delayed.configs.graphics().genericRendering().cloudRenderingEnabled();
		val.setValue(false);
		val.addChangeListener(b -> {
			if (b)
				val.setValue(false);
		});
		
		// The 1.20.1 MinecraftForge.EVENT_BUS.register(SimpleCloudsDhForgeEvents) is
		// intentionally NOT ported: both of its events (pipeline override, render-
		// distance clamp) are no-ops in the v2 26.2 renderer -- the clouds are drawn
		// in the world phase and the field is a fixed size, so neither applies.
	}
	
	public static Matrix4f dhMat4ToMc(DhApiMat4f mat4)
	{
		return new Matrix4f(
				mat4.m00, mat4.m01, mat4.m02, mat4.m03,
				mat4.m10, mat4.m11, mat4.m12, mat4.m13,
				mat4.m20, mat4.m21, mat4.m22, mat4.m23,
				mat4.m30, mat4.m31, mat4.m32, mat4.m33
		);
	}
}

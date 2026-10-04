package dev.nonamecrackers2.simpleclouds.client.dh.event;

import org.joml.Matrix4f;

import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeApplyShaderRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;

import dev.nonamecrackers2.simpleclouds.client.dh.SimpleCloudsDhCompatHandler;

/**
 * 26.2: caches DH's projection / model-view matrices for the current pass.
 *
 * The 1.20.1 original then re-rendered the clouds into DH's framebuffer using these
 * matrices (far-field LOD depth integration). In the v2 26.2 renderer that step is
 * intentionally a no-op: the clouds are drawn into the MAIN framebuffer during the
 * world phase, and the 26.2 RenderPass API cannot target DH's raw GL framebuffer
 * (the API only exposes the FBO id, not the GpuTextureView attachments). The cached
 * matrices are kept so a future far-field pass can use them.
 */
public class SimpleCloudsBeforeDhRenderHandler extends DhApiBeforeApplyShaderRenderEvent
{
	private static int debugFrames;
	@Override
	public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event)
	{
		DhApiRenderParam params = event.value;
		Matrix4f projMat = new Matrix4f().setTransposed(params.dhProjectionMatrix.getValuesAsArray());
		Matrix4f modelView = new Matrix4f().setTransposed(params.mcModelViewMatrix.getValuesAsArray());
		SimpleCloudsDhCompatHandler._updateCachedDhState(projMat, modelView);
		if ("1".equals(System.getenv("SIMPLECLOUDS_DEV")) && "1".equals(System.getenv("SIMPLECLOUDS_TEST_DH_MERGE_DEBUG")) && (debugFrames++ % 300) == 0)
			org.apache.logging.log4j.LogManager.getLogger("simpleclouds/DhDebug").info("[DH-MERGE-DEBUG] pass={} near={} far={} raw={} mat={}",
					params.renderPass, params.nearClipPlane, params.farClipPlane,
					java.util.Arrays.toString(params.dhProjectionMatrix.getValuesAsArray()), projMat);
		SimpleCloudsDhCompatHandler._markPassComplete(false);
		// v2 26.2: no cloud draw into DH's framebuffer here (see class javadoc).
	}
}

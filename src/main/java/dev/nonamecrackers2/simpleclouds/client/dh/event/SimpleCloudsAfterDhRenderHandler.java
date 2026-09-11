package dev.nonamecrackers2.simpleclouds.client.dh.event;

import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiAfterRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;

import dev.nonamecrackers2.simpleclouds.client.dh.SimpleCloudsDhCompatHandler;

/**
 * 26.2: end-of-DH-pass cleanup. The 1.20.1 original re-rendered the clouds (and the
 * lightning) into the scene using the cached DH matrices and DH's LOD depth before
 * resetting the cached state. In the v2 26.2 renderer the cloud draw is a no-op
 * (see SimpleCloudsBeforeDhRenderHandler), so this only resets the cached state so a
 * stale matrix/FBO is never read by the next pass.
 */
public class SimpleCloudsAfterDhRenderHandler extends DhApiAfterRenderEvent
{
	@Override
	public void afterRender(DhApiEventParam<Void> event)
	{
		if (SimpleCloudsDhCompatHandler._isPassComplete())
			return;
		// v2 26.2: no cloud/lightning draw into DH's scene here (see class javadoc).
		SimpleCloudsDhCompatHandler._updateDhFramebufferId(0);
		SimpleCloudsDhCompatHandler._updateCachedDhState(null, null);
		SimpleCloudsDhCompatHandler._markPassComplete(true);
	}
}

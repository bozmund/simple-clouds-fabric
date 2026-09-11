package dev.nonamecrackers2.simpleclouds.client.dh.event;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderPassEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;

import dev.nonamecrackers2.simpleclouds.client.dh.SimpleCloudsDhCompatHandler;

/**
 * 26.2: caches the framebuffer DH has bound at the start of its render pass. The
 * 1.20.1 original threw when no FBO was bound; in 26.2 the v2 renderer never draws
 * into DH's framebuffer (it draws its finite cloud field into the main framebuffer
 * during the world phase), so a missing FBO is non-fatal -- we just log it.
 */
public class SimpleCloudsDhSetupHandler extends DhApiBeforeRenderPassEvent
{
	@Override
	public void beforeRender(DhApiEventParam<DhApiRenderParam> event)
	{
		int id = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
		if (id == 0)
		{
			// No DH framebuffer bound at this point; the v2 world does not need it,
			// so this is not an error.
			return;
		}
		SimpleCloudsDhCompatHandler._updateDhFramebufferId(id);
	}
}

package nonamecrackers2.crackerslib.client.gui.widget;

import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import nonamecrackers2.crackerslib.client.util.RenderUtil;

/**
 * 26.2 port (PARTIAL — 3D previewer deferred).
 * API preserved; renderAs3D stubbed (needs 26.2 render pipeline).
 */
public abstract class Widget3D extends AbstractWidget
{
	protected @Nullable Matrix4f poseMatrix;
	protected Vector2f screenPos;
	protected Vector3f pos;

	public Widget3D(Vector3f pos, int screenX, int screenY, int width, int height, Component name)
	{
		super(screenX, screenY, width, height, name);
		this.pos = pos;
	}

	public void setPos(Vector3f pos)
	{
		this.pos = pos;
	}

	protected void updatePoseMatrix(Matrix4f poseMatrix)
	{
		this.poseMatrix = poseMatrix;
	}

	protected final Vector2f convertPosToScreenCoord(Vector3f pos)
	{
		Objects.requireNonNull(this.poseMatrix, "Previous 3D pose matrix is null!");
		return RenderUtil.getScreenCoordinatesFromWorldPos(this.poseMatrix, pos);
	}

	protected final Vector3f convertPosToScreenCordWithZDist(Vector3f pos)
	{
		Objects.requireNonNull(this.poseMatrix, "Previous 3D pose matrix is null!");
		return RenderUtil.getScreenCoordinatesFromWorldPosWithZDist(this.poseMatrix, pos);
	}

	/**
	 * 3D previewer deferred: needs the 26.2 render pipeline.
	 */
	public void renderAs3D(Object stack, Object buffers, int mouseX, int mouseY, float partialTick)
	{
		// TODO(3D-preview): port to 26.2 render pipeline
		this.updatePos();
	}

	protected void updatePos()
	{
		if (this.screenPos != null)
		{
			this.setX((int)this.screenPos.x);
			this.setY((int)this.screenPos.y);
		}
	}
}

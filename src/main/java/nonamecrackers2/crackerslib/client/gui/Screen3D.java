package nonamecrackers2.crackerslib.client.gui;

import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.mojang.math.Axis;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * 26.2 port (PARTIAL — 3D previewer deferred).
 * Camera/interaction API preserved; the 3D render pass is stubbed and needs the
 * new 26.2 render pipeline when the 3D previewer is ported.
 */
public abstract class Screen3D extends Screen
{
	protected final float farPlane;
	protected final float zoomConstant;
	protected float camRotX = 180.0F + 45.0F;
	protected float camRotY = 45.0F;
	protected float zoom = 1.0F;
	protected Vector3f offset = new Vector3f(0.0F, 0.0F, 0.0F);
	protected @Nullable Matrix4f poseMatrix;
	protected @Nullable Vector3f hitPos;
	protected @Nullable Vector3f lastDragPos;
	protected boolean renderOrigin;
	protected int moveForTime;
	protected float moveFor;
	protected @Nullable Vector3f moveFrom;
	protected @Nullable Supplier<Vector3f> moveTo;
	protected float initialZoom;
	protected float finalZoom;

	protected Screen3D(Component title, float zoomConstant, float farPlane)
	{
		super(title);
		this.farPlane = farPlane;
		this.zoomConstant = zoomConstant;
	}

	/** Camera state accessors (26.2 previewer pipeline builds the view matrix from these). */
	public float camRotX() { return this.camRotX; }
	public float camRotY() { return this.camRotY; }
	public float zoom() { return this.zoom; }
	public Vector3f offset() { return this.offset; }

	protected void renderOrigin(boolean flag)
	{
		this.renderOrigin = flag;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY)
	{
		if (!super.mouseDragged(event, dragX, dragY))
		{
			int button = event.button(); // 26.2: int (0=LEFT, 1=MIDDLE, 2=RIGHT)
			if (button == 2)
			{
				if (this.canRotate())
				{
					this.camRotX = Mth.clamp(this.camRotX + (float)dragY, 90.0F, 270.0F);
					this.camRotY = (float)Mth.wrapDegrees((double)this.camRotY + dragX);
					this.onRotate();
				}
			}
			else if (button == 0)
			{
				if (this.canMove())
				{
					Vector3f move = new Vector3f((float)-dragX / (this.zoom * this.zoomConstant), (float)dragY / (this.zoom * this.zoomConstant), 0.0F);
					move.rotate(Axis.XP.rotationDegrees(this.camRotX));
					move.rotate(Axis.YN.rotationDegrees(this.camRotY));
					this.offset.add(move);
					this.onMove();
				}
			}
			return false;
		}
		else
		{
			return true;
		}
	}

	@Override
	public boolean mouseScrolled(double x, double y, double deltaX, double deltaY)
	{
		if (!super.mouseScrolled(x, y, deltaX, deltaY))
		{
			if (this.canZoom())
			{
				this.zoom = Math.max(this.zoom + (float)deltaY * (this.zoom / 10.0F), 1.0F);
				this.onZoom();
			}
			return false;
		}
		else
		{
			return true;
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor stack, int pMouseX, int pMouseY, float pPartialTick)
	{
		// Handle move animation
		if (this.moveFor > 0.0F)
		{
			this.moveFor -= pPartialTick * Math.max(this.moveFor / (float)this.moveForTime, 0.0001F);
			if (this.moveForTime > 0 && this.moveTo != null && this.moveFrom != null)
			{
				Vector3f finalPos = this.moveTo.get();
				if (finalPos != null)
				{
					float transition = this.moveFor / (float)this.moveForTime;
					float x = Mth.lerp(transition, finalPos.x, this.moveFrom.x);
					float y = Mth.lerp(transition, finalPos.y, this.moveFrom.y);
					float z = Mth.lerp(transition, finalPos.z, this.moveFrom.z);
					this.offset = new Vector3f(x, y, z);
					this.zoom = Mth.lerp(transition, this.finalZoom, this.initialZoom);
				}
			}
			if (this.moveFor <= 0.0F)
			{
				this.moveFrom = null;
				this.moveForTime = 0;
			}
		}
		else
		{
			if (this.moveTo != null)
				this.offset = this.moveTo.get();
		}

		// 26.2 3D previewer: subclasses draw their 3D content here (GUI phase,
		// after the world frame graph). The extractor is needed for GUI blits.
		this.render3D(stack, pMouseX, pMouseY, pPartialTick);

		super.extractRenderState(stack, pMouseX, pMouseY, pPartialTick);
	}

	/**
	 * 3D previewer deferred: signature simplified (no MultiBufferSource/PoseStack).
	 * Subclasses must be updated when the 3D previewer is ported.
	 */
	protected void render3D(Object stack, int mouseX, int mouseY, float partialTick) {}

	protected boolean canZoom()
	{
		return this.moveFor <= 0.0F;
	}

	protected boolean canMove()
	{
		return this.moveFor <= 0.0F;
	}

	protected boolean canRotate() { return true; }

	protected void onZoom() {}

	protected void onMove()
	{
		this.moveTo = null;
	}

	protected void onRotate() {}

	protected void lerpTo(int time, Supplier<Vector3f> pos, float zoom)
	{
		this.moveFor = (float)time;
		this.moveForTime = time;
		this.moveTo = pos;
		this.moveFrom = this.offset;
		this.initialZoom = this.zoom;
		this.finalZoom = zoom;
	}
}

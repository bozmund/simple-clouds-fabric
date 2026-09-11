package nonamecrackers2.crackerslib.client.util;

import java.util.List;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

/**
 * 26.2 port (PARTIAL — 3D previewer deferred).
 * Math helpers preserved. GPU render helpers (line, spheres, projection matrix)
 * are stubbed; they need the new 26.2 render pipeline when the 3D previewer is ported.
 */
public class RenderUtil
{
	private RenderUtil() {}

	public static void setClipPlanes(Matrix4f mat, float near, float far)
	{
		mat.set(2, 2, -((far + near) / (far - near))).set(3, 2, -((2 * far * near) / (far - near)));
	}

	public static void renderCenteredWordWrap(GuiGraphicsExtractor stack, Font font, FormattedText text, int x, int y, int width, int color)
	{
		List<FormattedCharSequence> texts = font.split(text, width);
		int totalHeight = texts.size() * (font.lineHeight + 2);
		for (int i = 0; i < texts.size(); i++)
			stack.centeredText(font, texts.get(i), x, y + i * font.lineHeight + 2 - totalHeight / 2, color);
	}

	public static void renderHorizontallyCenteredWordWrap(GuiGraphicsExtractor stack, Font font, FormattedText text, int x, int y, int width, int color)
	{
		List<FormattedCharSequence> texts = font.split(text, width);
		for (int i = 0; i < texts.size(); i++)
			stack.centeredText(font, texts.get(i), x, y + i * font.lineHeight + 2, color);
	}

	// 3D previewer deferred: GPU line rendering needs the 26.2 render pipeline.
	public static void line(GuiGraphicsExtractor stack, Vector2f start, Vector2f end, int blitOffset, float lineWidth, float r, float g, float b, float a)
	{
		// TODO(3D-preview): port to 26.2 render pipeline
	}

	public static Vector3f getWorldPosFromScreenPos(Matrix4f mat, int screenX, int screenY, float z)
	{
		Vector4f vec = new Vector4f(screenX, screenY, z, 1.0F);
		mat.invert().transform(vec);
		return new Vector3f(vec.x / vec.w, vec.y / vec.w, vec.z / vec.w);
	}

	public static Vector2f getScreenCoordinatesFromWorldPos(Matrix4f mat, Vector3f pos)
	{
		Minecraft mc = Minecraft.getInstance();
		Vector4f vec = new Vector4f(pos.x, pos.y, pos.z, 1.0F);
		mat.transform(vec);
		float x = (float)mc.getWindow().getWidth() / 2 + (float)((double)vec.x / (double)vec.w * (double)mc.getWindow().getWidth() / 2.0D);
		float y = (float)mc.getWindow().getHeight() / 2 - (float)((double)vec.y / (double)vec.w * (double)mc.getWindow().getHeight() / 2.0D);
		return new Vector2f(x, y);
	}

	public static Vector3f getScreenCoordinatesFromWorldPosWithZDist(Matrix4f mat, Vector3f pos)
	{
		Minecraft mc = Minecraft.getInstance();
		Vector4f vec = new Vector4f(pos.x, pos.y, pos.z, 1.0F);
		mat.transform(vec);
		float x = (float)mc.getWindow().getWidth() / 2 + (float)((double)vec.x / (double)vec.w * (double)mc.getWindow().getWidth() / 2.0D);
		float y = (float)mc.getWindow().getHeight() / 2 - (float)((double)vec.y / (double)vec.w * (double)mc.getWindow().getHeight() / 2.0D);
		return new Vector3f(x, y, vec.z / vec.w);
	}

	public static float getScreenZCoord(Matrix4f mat, Vector3f pos)
	{
		Vector4f vec = new Vector4f(pos.x, pos.y, pos.z, 1.0F);
		mat.transform(vec);
		return vec.z / vec.w;
	}

	// 3D previewer deferred: sphere rendering needs the 26.2 render pipeline.
	public static void renderCubeSphere(int subdivision, float radius, Object stack, Object consumer, int packedLight, int overlayTexture, boolean useNormals)
	{
		// TODO(3D-preview): port to 26.2 render pipeline
	}

	public static void renderSectorStackSphere(float radius, int sectorCount, int stackCount, Object stack, Object consumer, int packedLight, int overlayTexture)
	{
		// TODO(3D-preview): port to 26.2 render pipeline
	}

	public static boolean isMouseInBounds(int mouseX, int mouseY, int x, int y, int width, int height)
	{
		return mouseX > x && mouseY > y && mouseX < x + width && mouseY < y + height;
	}

	public static boolean isMouseInBounds(int mouseX, int mouseY, ScreenRectangle rectangle)
	{
		return isMouseInBounds(mouseX, mouseY, rectangle.position().x(), rectangle.position().y(), rectangle.width(), rectangle.height());
	}

	// 3D previewer deferred: projection matrix adjustment needs the 26.2 render pipeline.
	public static void adjustProjectionMatrix(float partialTicks, float near, float far)
	{
		// TODO(3D-preview): port to 26.2 render pipeline
	}

	public static void popAdjustedProjectionMatrix()
	{
		// TODO(3D-preview): port to 26.2 render pipeline
	}
}

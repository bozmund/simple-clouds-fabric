package dev.nonamecrackers2.simpleclouds.client.framebuffer;

import org.joml.Matrix4f;

/**
 * 26.2 vertical-slice stub. Shadow maps are not ported yet; this keeps the type
 * API-compatible so the renderer getters and dependent code compile. Port incrementally.
 */
public class ShadowMapBuffer implements AutoCloseable
{
	private final int viewWidth;
	private final int viewHeight;
	private final int texWidth;
	private final int texHeight;
	private final float near;
	private final float far;
	private final boolean withColor;

	public ShadowMapBuffer(int viewWidth, int viewHeight, int texWidth, int texHeight, float near, float far, boolean withColor, boolean comparisonDepth)
	{
		this.viewWidth = viewWidth;
		this.viewHeight = viewHeight;
		this.texWidth = texWidth;
		this.texHeight = texHeight;
		this.near = near;
		this.far = far;
		this.withColor = withColor;
	}

	public void bind()
	{
	}

	public void clear(boolean osx)
	{
	}

	public void unbind()
	{
	}

	public float getNear()
	{
		return this.near;
	}

	public float getFar()
	{
		return this.far;
	}

	public int getViewWidth()
	{
		return this.viewWidth;
	}

	public int getViewHeight()
	{
		return this.viewHeight;
	}

	public int getTexWidth()
	{
		return this.texWidth;
	}

	public int getTexHeight()
	{
		return this.texHeight;
	}

	public int getFramebufferId()
	{
		return 0;
	}

	public int getDepthTexId()
	{
		return 0;
	}

	public int getColorTexId()
	{
		return 0;
	}

	public boolean hasColor()
	{
		return this.withColor;
	}

	public Matrix4f getProjMatrix()
	{
		return new Matrix4f().identity();
	}

	@Override
	public void close()
	{
	}

	@Override
	public String toString()
	{
		return "ShadowMapBuffer[stub]";
	}
}

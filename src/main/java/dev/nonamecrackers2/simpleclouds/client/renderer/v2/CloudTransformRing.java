package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import java.nio.ByteBuffer;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.device.GpuDevice;

import net.minecraft.client.renderer.MappableRingBuffer;

/**
 * The private ring of {@code DynamicTransforms} UBO slices the cloud and rain passes draw from.
 *
 * <p>Minecraft 26.3 removed {@code net.minecraft.client.renderer.DynamicUniforms} and
 * {@code DynamicUniformStorage}; only {@link MappableRingBuffer} survived. Simple Clouds used
 * exactly two methods of the old class ({@code writeTransform} and {@code reset}) on its own
 * private instance, so this is that much of it and nothing else.
 *
 * <p>Why a private ring at all (unchanged from 26.2): our passes are encoded at the tail of the
 * level render but execute later in the frame, and vanilla's shared ring can be re-written in
 * between — which zeroed {@code ModelViewMat} / {@code ColorModulator} and made every cloud
 * fragment vanish. {@link #reset()} is called once per frame and rotates the ring, so slices
 * handed out for earlier frames stay intact while the GPU still reads them.
 *
 * <p>The block layout is std140 as declared in {@code assets/minecraft/shaders/include/
 * dynamictransforms.glsl}, which 26.3 did not change:
 * {@code mat4 ModelViewMat} (0), {@code mat4 TextureMat} (64), {@code vec4 ColorModulator} (128),
 * {@code vec3 ModelOffset} (144) — 160 bytes, the same size the old class reported.
 */
public class CloudTransformRing implements AutoCloseable
{
	public static final int TRANSFORM_UBO_SIZE = 160;
	private static final int MODEL_VIEW_OFFSET = 0;
	private static final int TEXTURE_MAT_OFFSET = 64;
	private static final int COLOR_MODULATOR_OFFSET = 128;
	private static final int MODEL_OFFSET_OFFSET = 144;
	private static final Matrix4f IDENTITY = new Matrix4f();

	private final MappableRingBuffer ring;
	/** Slice spacing: the UBO size rounded up to the device's uniform offset alignment. */
	private final int stride;
	private final int slots;
	private int position;

	public CloudTransformRing(String name, int slots)
	{
		GpuDevice device = RenderSystem.getDevice();
		int alignment = Math.max(1, device.getDeviceInfo().limits().minUniformOffsetAlignment());
		this.stride = (TRANSFORM_UBO_SIZE + alignment - 1) / alignment * alignment;
		this.slots = slots;
		this.ring = new MappableRingBuffer(() -> name, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE,
				this.stride * slots);
	}

	/** Once per rendered frame: rewind and rotate to the next buffer of the ring. */
	public void reset()
	{
		this.position = 0;
		this.ring.rotate();
	}

	public GpuBufferSlice writeTransform(Matrix4f modelView)
	{
		return this.write(modelView, null);
	}

	public GpuBufferSlice writeTransform(Matrix4f modelView, Vector4f color)
	{
		return this.write(modelView, color);
	}

	/** Null when this frame has already used every slot (the caller reports the overflow). */
	private GpuBufferSlice write(Matrix4f modelView, Vector4f color)
	{
		if (this.position + this.stride > this.stride * this.slots)
			return null;
		GpuBuffer buffer = this.ring.currentBuffer();
		int offset = this.position;
		try (GpuBufferSlice.MappedView view = buffer.map(offset, TRANSFORM_UBO_SIZE, false, true))
		{
			ByteBuffer data = view.data();
			modelView.get(MODEL_VIEW_OFFSET, data);
			IDENTITY.get(TEXTURE_MAT_OFFSET, data);
			float r = color == null ? 1.0F : color.x;
			float g = color == null ? 1.0F : color.y;
			float b = color == null ? 1.0F : color.z;
			float a = color == null ? 1.0F : color.w;
			data.putFloat(COLOR_MODULATOR_OFFSET, r);
			data.putFloat(COLOR_MODULATOR_OFFSET + 4, g);
			data.putFloat(COLOR_MODULATOR_OFFSET + 8, b);
			data.putFloat(COLOR_MODULATOR_OFFSET + 12, a);
			data.putFloat(MODEL_OFFSET_OFFSET, 0.0F);
			data.putFloat(MODEL_OFFSET_OFFSET + 4, 0.0F);
			data.putFloat(MODEL_OFFSET_OFFSET + 8, 0.0F);
		}
		this.position += this.stride;
		return buffer.slice(offset, TRANSFORM_UBO_SIZE);
	}

	@Override
	public void close()
	{
		this.ring.close();
	}
}


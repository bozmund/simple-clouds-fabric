package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import dev.nonamecrackers2.simpleclouds.client.noise.PsrdNoise;

/**
 * Vertical-slice (26.2) CPU cloud generator.
 *
 * Ports the core of {@code cube_mesh.comp} to the CPU: it iterates a voxel grid,
 * samples layered {@link PsrdNoise}, and emits one per-instance record per visible
 * cloud face. The new backend has no compute dispatch, so generation happens here
 * instead of on the GPU.
 *
 * Layers are grouped per cloud type ({@link CloudLayerGroup}), mirroring the
 * original's per-type {@code LayerGroup} in the compute shader: the opaque/transparent
 * decision is made per group (a cell can be opaque for one type and a transparent
 * edge for another), and the transparent alpha ramp uses the group's
 * {@code TransparencyFade} (the cloud type's {@code transparency_fade}).
 *
 * Instance layouts (match the vertex formats):
 * opaque: Side(float) + SidePos(vec3) + Radius(float) + Brightness(float) = 24 bytes.
 * transparent: + Alpha(float) = 28 bytes.
 */
public final class CpuCloudGenerator
{
	/** A single noise layer (mirrors the GLSL NoiseLayer struct / AbstractNoiseSettings.Param). */
	public record NoiseLayer(float height, float valueOffset, float scaleX, float scaleY, float scaleZ,
			float fadeDistance, float heightOffset, float valueScale)
	{
	}

	/** All noise layers of one cloud type plus its transparency fade (a compute-shader LayerGroup). */
	public record CloudLayerGroup(List<NoiseLayer> layers, float transparencyFade, boolean stormType)
	{
	}

	private static final float TILE_PERIOD_X = 32.0F;
	private static final float TILE_PERIOD_Y = 64.0F;
	private static final float TILE_PERIOD_Z = 32.0F;

	private List<CloudLayerGroup> groups;

	public CpuCloudGenerator(List<CloudLayerGroup> groups)
	{
		this.groups = groups;
	}

	/** Replace the active group set. */
	public void setGroups(List<CloudLayerGroup> groups)
	{
		this.groups = groups;
	}

	/**
	 * Generates per-instance cloud data for the voxel grid [x0..x1) x [y0..y1) x [z0..z1)
	 * at the given scale/scroll.
	 *
	 * @return a two-element array: [opaque data or null, transparent data or null]
	 *         (native-endian floats).
	 */
	/**
	 * @param cameraGridY the camera's Y in grid units; used for the storm-coverage metric
	 * @param outStormCoverage [0] = fraction of the 8x8 columns around the camera center
	 *                         that contain at least one opaque storm-type cell above the camera
	 */
	public ByteBuffer[] generate(int x0, int y0, int z0, int x1, int y1, int z1, float scale, float scrollX, float scrollY, float scrollZ, float wiggle, float[] outOpaqueCount, float[] outTransparentCount, int cameraGridY, float[] outStormCoverage)
	{
		int cells = (x1 - x0) * (y1 - y0) * (z1 - z0);
		ByteBuffer opaqueBuffer = ByteBuffer.allocateDirect(Math.max(256, cells * 6 * CloudVertexFormat.BYTES_PER_INSTANCE)).order(ByteOrder.nativeOrder());
		// A cell can emit up to one transparent cube per group (groups overlap in Y), so the
		// one-cube-per-cell capacity is a lower bound; grow on demand.
		ByteBuffer transparentBuffer = ByteBuffer.allocateDirect(Math.max(256, cells * 6 * CloudVertexFormat.BYTES_PER_INSTANCE_ALPHA)).order(ByteOrder.nativeOrder());
		int opaqueWritten = 0;
		int transparentWritten = 0;
		float[] gradient = new float[3];
		int groupCount = this.groups.size();
		float[] groupNoises = new float[groupCount];
		boolean[] columnStorm = new boolean[(x1 - x0) * (z1 - z0)];

		for (int x = x0; x < x1; x++)
		{
			for (int y = y0; y < y1; y++)
			{
				for (int z = z0; z < z1; z++)
				{
					boolean anyOpaque = false;
					for (int g = 0; g < groupCount; g++)
					{
						float noise = sampleGroup(this.groups.get(g), x, y, z, scale, scrollX, scrollY, scrollZ, wiggle, gradient);
						groupNoises[g] = noise;
						if (noise > 0.0F)
							anyOpaque = true;
					}

					float brightness = 1.0F; // vertical slice: no storm darkening yet
					if (anyOpaque)
					{
						// Opaque cube (port of createCube): one per cell even when several
						// groups are opaque (identical geometry; the original emitted one
						// cube per group, which just overlapped).
						opaqueWritten += emitVisibleFaces(opaqueBuffer, opaqueWritten, x, y, z, scale, brightness, scrollX, scrollY, scrollZ, wiggle);

						// Storm-coverage metric: does this column have storm-type cloud
						// above the camera? (drives the 26.2 slice storm fog intensity)
						if (y > cameraGridY)
						{
							for (int g = 0; g < groupCount; g++)
							{
								if (groupNoises[g] > 0.0F && this.groups.get(g).stormType())
								{
									columnStorm[(x - x0) * (z1 - z0) + (z - z0)] = true;
									break;
								}
							}
						}
					}

					// Transparent edges (port of the TRANSPARENCY==1 block), per group and
					// INDEPENDENT of the opaque decision: in the original each group runs its
					// own if/else-if, so a group with noise in (-TransparencyFade, 0) emits a
					// full cube (all six faces, no culling) with an alpha ramp even on cells
					// where another group is opaque.
					for (int g = 0; g < groupCount; g++)
					{
						if (groupNoises[g] > 0.0F)
							continue;
						CloudLayerGroup group = this.groups.get(g);
						float fade = group.transparencyFade();
						float noise = groupNoises[g];
						if (fade > 0.01F && noise > -fade)
						{
							float alpha = (noise + fade) / fade;
							int needed = 6 * CloudVertexFormat.BYTES_PER_INSTANCE_ALPHA;
							if (transparentWritten + needed > transparentBuffer.capacity())
								transparentBuffer = grow(transparentBuffer, transparentWritten, transparentBuffer.capacity() * 2);
							transparentWritten += emitTransparentCube(transparentBuffer, transparentWritten, x, y, z, scale, brightness, alpha);
						}
					}
				}
			}
		}

		// Fraction of the 8x8 central columns (around the camera) with storm above.
		int center = (x1 - x0) / 2;
		int marked = 0;
		for (int dx = center - 4; dx < center + 4; dx++)
		{
			for (int dz = center - 4; dz < center + 4; dz++)
			{
				if (columnStorm[dx * (z1 - z0) + dz])
					marked++;
			}
		}
		outStormCoverage[0] = marked / 64.0F;

		outOpaqueCount[0] = opaqueWritten / CloudVertexFormat.BYTES_PER_INSTANCE;
		outTransparentCount[0] = transparentWritten / CloudVertexFormat.BYTES_PER_INSTANCE_ALPHA;
		return new ByteBuffer[] { bound(opaqueBuffer, opaqueWritten), bound(transparentBuffer, transparentWritten) };
	}

	/** Copies a scratch buffer (bytes 0..size) into a larger one. */
	private static ByteBuffer grow(ByteBuffer buffer, int size, int newCapacity)
	{
		ByteBuffer grown = ByteBuffer.allocateDirect(newCapacity).order(buffer.order());
		buffer.position(0);
		buffer.limit(size);
		grown.put(buffer);
		return grown;
	}

	/** Binds a scratch buffer to the bytes written (null when empty). */
	private static ByteBuffer bound(ByteBuffer buffer, int written)
	{
		if (written == 0)
			return null;
		buffer.position(0);
		buffer.limit(written);
		ByteBuffer result = ByteBuffer.allocateDirect(written).order(ByteOrder.nativeOrder());
		result.put(buffer);
		result.flip();
		return result;
	}

	/** Combined layered noise for one group at a voxel position (port of getNoiseForLayerGroup). */
	private float sampleGroup(CloudLayerGroup group, int x, int y, int z, float scale, float scrollX, float scrollY, float scrollZ, float wiggle, float[] gradient)
	{
		float combined = 0.0F;
		float gx = 0.0F, gy = 0.0F, gz = 0.0F;
		boolean anyValid = false;
		for (NoiseLayer layer : group.layers())
		{
			float value = sampleLayer(layer, x, y, z, scale, scrollX, scrollY, scrollZ, wiggle, gradient);
			if (value > -10.0F)
			{
				combined += value;
				gx += gradient[0];
				gy += gradient[1];
				gz += gradient[2];
				anyValid = true;
			}
		}
		gradient[0] = gx;
		gradient[1] = gy;
		gradient[2] = gz;
		return anyValid ? combined : -10000.0F;
	}

	/** Single-layer noise (port of getNoiseForLayer). */
	private float sampleLayer(NoiseLayer layer, int x, int y, int z, float scale, float scrollX, float scrollY, float scrollZ, float wiggle, float[] gradient)
	{
		if (y < layer.heightOffset() || y > layer.heightOffset() + layer.height() - 1)
			return -10000.0F;

		float sx = x / layer.scaleX();
		float sy = y / layer.scaleY();
		float sz = z / layer.scaleZ();
		float px = sx + scrollX / layer.scaleX();
		float py = sy + scrollY / layer.scaleY();
		float pz = sz + scrollZ / layer.scaleZ();

		float noise = PsrdNoise.noise(px, py, pz, TILE_PERIOD_X, TILE_PERIOD_Y, TILE_PERIOD_Z, wiggle, gradient)
				* layer.valueScale() + layer.valueOffset();

		float heightDelta = y - layer.heightOffset();
		noise -= 1.0F - clamp(heightDelta / layer.fadeDistance(), 0.0F, 1.0F);
		noise -= 1.0F - clamp((layer.height() - heightDelta) / layer.fadeDistance(), 0.0F, 1.0F);
		return noise;
	}

	/** Emits visible faces for an opaque cloud voxel (port of createCube), returning bytes written. */
	private int emitVisibleFaces(ByteBuffer buffer, int offset, int x, int y, int z, float scale, float brightness,
			float scrollX, float scrollY, float scrollZ, float wiggle)
	{
		float radius = scale / 2.0F;
		// SidePos must be in WORLD coordinates (the shader adds it to the view-space
		// position untransformed). Grid cell (x, y, z) spans world [x*scale, (x+1)*scale);
		// its center is (x + radius) * scale. Emitting grid units only looked right near
		// the origin (where grid ≈ world / 2 ≈ world), which masked this.
		float cx = (x + radius) * scale, cy = (y + radius) * scale, cz = (z + radius) * scale;
		int written = 0;

		// Face order matches the shader: -X=0, +X=1, -Y=2, +Y=3, -Z=4, +Z=5.
		written += this.emitFaceIfVisible(buffer, offset + written, 0, cx, cy, cz, radius, brightness, this.isValid(x - scale, y, z, scale, scrollX, scrollY, scrollZ, wiggle));
		written += this.emitFaceIfVisible(buffer, offset + written, 1, cx, cy, cz, radius, brightness, this.isValid(x + scale, y, z, scale, scrollX, scrollY, scrollZ, wiggle));
		written += this.emitFaceIfVisible(buffer, offset + written, 2, cx, cy, cz, radius, brightness, this.isValid(x, y - scale, z, scale, scrollX, scrollY, scrollZ, wiggle));
		written += this.emitFaceIfVisible(buffer, offset + written, 3, cx, cy, cz, radius, brightness, this.isValid(x, y + scale, z, scale, scrollX, scrollY, scrollZ, wiggle));
		written += this.emitFaceIfVisible(buffer, offset + written, 4, cx, cy, cz, radius, brightness, this.isValid(x, y, z - scale, scale, scrollX, scrollY, scrollZ, wiggle));
		written += this.emitFaceIfVisible(buffer, offset + written, 5, cx, cy, cz, radius, brightness, this.isValid(x, y, z + scale, scale, scrollX, scrollY, scrollZ, wiggle));
		return written;
	}

	private int emitFaceIfVisible(ByteBuffer buffer, int offset, int side, float cx, float cy, float cz, float radius, float brightness, boolean neighborIsCloud)
	{
		if (neighborIsCloud)
			return 0;
		buffer.putFloat(offset, side);
		buffer.putFloat(offset + 4, cx);
		buffer.putFloat(offset + 8, cy);
		buffer.putFloat(offset + 12, cz);
		buffer.putFloat(offset + 16, radius);
		buffer.putFloat(offset + 20, brightness);
		return CloudVertexFormat.BYTES_PER_INSTANCE;
	}

	/** Emits all six faces of a transparent voxel (port of createTransparentCube), returning bytes written. */
	private int emitTransparentCube(ByteBuffer buffer, int offset, int x, int y, int z, float scale, float brightness, float alpha)
	{
		float radius = scale / 2.0F;
		float cx = (x + radius) * scale, cy = (y + radius) * scale, cz = (z + radius) * scale;
		int written = 0;
		for (int side = 0; side < 6; side++)
		{
			buffer.putFloat(offset + written, side);
			buffer.putFloat(offset + written + 4, cx);
			buffer.putFloat(offset + written + 8, cy);
			buffer.putFloat(offset + written + 12, cz);
			buffer.putFloat(offset + written + 16, radius);
			buffer.putFloat(offset + written + 20, brightness);
			buffer.putFloat(offset + written + 24, alpha);
			written += CloudVertexFormat.BYTES_PER_INSTANCE_ALPHA;
		}
		return written;
	}

	/** Whether a position is inside the cloud (port of isPosValid, simplified: no region/fade). */
	private boolean isValid(float x, float y, float z, float scale, float scrollX, float scrollY, float scrollZ, float wiggle)
	{
		float[] gradient = new float[3];
		float combined = 0.0F;
		boolean any = false;
		for (CloudLayerGroup group : this.groups)
		{
			for (NoiseLayer layer : group.layers())
			{
				float value = sampleLayer(layer, (int) Math.round(x), (int) Math.round(y), (int) Math.round(z), scale, scrollX, scrollY, scrollZ, wiggle, gradient);
				if (value > -10.0F)
				{
					combined += value;
					any = true;
				}
			}
		}
		return any && combined > 0.0F;
	}

	private static float clamp(float v, float min, float max)
	{
		return v < min ? min : Math.min(v, max);
	}
}

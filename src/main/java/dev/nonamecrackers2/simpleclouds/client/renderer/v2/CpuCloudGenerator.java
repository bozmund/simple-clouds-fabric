package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
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
 * Two modes (matching the original's two compute programs):
 * <ul>
 * <li><b>Region mode</b> (formation list non-empty): the world-fixed noise field is
 * masked by the spawned formations' X/Z footprints (port of {@code cloud_regions.comp}:
 * per-column coverage g, noise offset -5 * (1-g)^10). A column with no formation
 * emits nothing — this is what makes clouds discrete formations instead of one
 * continuous sheet.</li>
 * <li><b>Infinite-field mode</b> (no formations): every active group is sampled in
 * the whole band. Used by the 3D previewer and as a fallback before the first
 * formation exists.</li>
 * </ul>
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

	/**
	 * X/Z footprint of one spawned cloud formation (a compute-shader CloudRegion entry).
	 * x/z/radius are in WORLD BLOCKS; m00..m11 are the region's rotation+stretch
	 * transform (CloudRegion.createTransform, dimensionless); groupIndex is the index
	 * of the formation's cloud type in the active group list.
	 */
	public record RegionMask(float x, float z, float radius, float m00, float m01, float m10, float m11, int groupIndex)
	{
	}

	private static final float TILE_PERIOD_X = 32.0F;
	private static final float TILE_PERIOD_Y = 64.0F;
	private static final float TILE_PERIOD_Z = 32.0F;
	// SimpleCloudsConstants.REGION_EDGE_FADE_FACTOR (cloud_regions.comp's EFF).
	private static final float REGION_EDGE_FADE_FACTOR = 0.005F;

	private List<CloudLayerGroup> groups;
	// Empty = infinite-field mode (previewer / no formations yet). Non-empty = region
	// mode: the noise field is masked by the formations' footprints.
	private List<RegionMask> regions = List.of();

	public CpuCloudGenerator(List<CloudLayerGroup> groups)
	{
		this.groups = groups;
	}

	/** Replace the active group set. */
	public void setGroups(List<CloudLayerGroup> groups)
	{
		this.groups = groups;
	}

	/** Replace the active formation footprints (empty list = infinite field). */
	public void setRegions(List<RegionMask> regions)
	{
		this.regions = List.copyOf(regions);
	}

	/**
	 * Generates per-instance cloud data for the voxel grid [x0..x1) x [y0..y1) x [z0..z1)
	 * at the given scale/scroll.
	 *
	 * @param cameraGridY the camera's Y in grid units; used for the storm-coverage metric
	 * @param outStormCoverage [0] = fraction of the 8x8 columns around the camera center
	 *                         that contain at least one opaque storm-type cell above the camera
	 * @return a two-element array: [opaque data or null, transparent data or null]
	 *         (native-endian floats, position 0, limit = bytes written).
	 */
	public ByteBuffer[] generate(int x0, int y0, int z0, int x1, int y1, int z1, float scale, float scrollX, float scrollY, float scrollZ, float wiggle, float[] outOpaqueCount, float[] outTransparentCount, int cameraGridY, float[] outStormCoverage)
	{
		// Parity with 1.20.1: the noise volume is anchored 128 blocks (the cloudHeight
		// config) BELOW the camera and spans 256 units (VERTICAL_CHUNK_SPAN * CHUNK_SIZE).
		// Layer heights/offsets and the noise Y coordinate are RELATIVE to the volume
		// base (y0), so clouds follow the player's altitude. x/z stay world-fixed.
		this.yBase = y0;
		int xSpan = x1 - x0, ySpan = y1 - y0, zSpan = z1 - z0;
		int cells = xSpan * ySpan * zSpan;
		// Growable scratch buffers: only a small fraction of cells is filled, so start
		// small (a full 256-unit band would otherwise preallocate ~80 MB) and grow on demand.
		ByteBuffer opaqueBuffer = ByteBuffer.allocateDirect(Math.max(256, cells / 64 * 6 * CloudVertexFormat.BYTES_PER_INSTANCE)).order(ByteOrder.nativeOrder());
		// A cell can emit up to one transparent cube per group (groups overlap in Y), so the
		// one-cube-per-cell capacity is a lower bound; grow on demand.
		ByteBuffer transparentBuffer = ByteBuffer.allocateDirect(Math.max(256, cells / 64 * 6 * CloudVertexFormat.BYTES_PER_INSTANCE_ALPHA)).order(ByteOrder.nativeOrder());
		int opaqueWritten = 0;
		int transparentWritten = 0;
		float[] gradient = new float[3];
		int groupCount = this.groups.size();
		float[] groupNoises = new float[groupCount];
		boolean[] columnStorm = new boolean[xSpan * zSpan];

		// Region mode: precompute the per-column formation mask (port of cloud_regions.comp).
		// Regions are 2D, so this is xz-sized, not volume-sized.
		boolean regionMode = !this.regions.isEmpty();
		int[] columnGroup = regionMode ? new int[xSpan * zSpan] : null;
		float[] columnFade = regionMode ? new float[xSpan * zSpan] : null;
		if (regionMode)
		{
			Arrays.fill(columnGroup, -1);
			float edge = 1.0F / REGION_EDGE_FADE_FACTOR;
			for (int x = x0; x < x1; x++)
			{
				for (int z = z0; z < z1; z++)
				{
					// Cloud units (1 unit = CLOUD_SCALE = 8 world blocks): the region
					// positions/radii and the noise coordinates share this space, and the
					// grid cell id at scale=8 is exactly a cloud-unit coordinate.
					float wx = x + 0.5F, wz = z + 0.5F;
					int best = -1;
					float bestG = 0.0F;
					for (RegionMask r : this.regions)
					{
						float dx = wx - r.x(), dz = wz - r.z();
						float tx = r.m00() * dx + r.m01() * dz;
						float tz = r.m10() * dx + r.m11() * dz;
						float d = (float) Math.sqrt(tx * tx + tz * tz);
						float radius = r.radius();
						if (d > radius + edge)
							continue;
						if (d < radius)
						{
							// Inside: the first (inner) region wins, like the shader's composite.
							if (best < 0)
							{
								best = r.groupIndex();
								bestG = Math.min((radius - d) * REGION_EDGE_FADE_FACTOR, 1.0F);
							}
						}
						else if (best >= 0)
						{
							// Outer falloff of a different region multiplies the coverage.
							bestG *= Math.min((d - radius) * REGION_EDGE_FADE_FACTOR, 1.0F);
						}
					}
					int i = (x - x0) * zSpan + (z - z0);
					columnGroup[i] = best;
					// cube_mesh.comp: fade = -5 * (1 - g)^10
					columnFade[i] = best < 0 ? 0.0F : -5.0F * (float) Math.pow(1.0F - bestG, 10.0);
				}
			}
		}

		for (int x = x0; x < x1; x++)
		{
			for (int y = y0; y < y1; y++)
			{
				for (int z = z0; z < z1; z++)
				{
					int ci = (x - x0) * zSpan + (z - z0);
					// Region mode: a column with no formation emits nothing.
					if (regionMode && columnGroup[ci] < 0)
						continue;

					boolean anyOpaque = false;
					for (int g = 0; g < groupCount; g++)
					{
						if (regionMode && g != columnGroup[ci])
							continue;
						float noise = sampleGroup(this.groups.get(g), x, y, z, scale, scrollX, scrollY, scrollZ, wiggle, gradient);
						if (regionMode)
							noise += columnFade[ci];
						groupNoises[g] = noise;
						if (noise > 0.0F)
							anyOpaque = true;
					}

					float brightness = 1.0F; // vertical slice: no storm darkening yet
					if (anyOpaque)
					{
						int needed = 6 * CloudVertexFormat.BYTES_PER_INSTANCE;
						if (opaqueWritten + needed > opaqueBuffer.capacity())
							opaqueBuffer = grow(opaqueBuffer, opaqueWritten, opaqueBuffer.capacity() * 2);
						// Opaque cube (port of createCube): one per cell even when several
						// groups are opaque (identical geometry; the original emitted one
						// cube per group, which just overlapped).
						opaqueWritten += regionMode
								? emitVisibleFacesRegion(opaqueBuffer, opaqueWritten, x, y, z, scale, brightness, x0, y0, z0, x1, y1, z1, columnGroup, columnFade, scale, scrollX, scrollY, scrollZ, wiggle, gradient)
								: emitVisibleFaces(opaqueBuffer, opaqueWritten, x, y, z, scale, brightness, scrollX, scrollY, scrollZ, wiggle);

						// Storm-coverage metric: does this column have storm-type cloud
						// above the camera? (drives the 26.2 slice storm fog intensity)
						if (y > cameraGridY)
						{
							for (int g = 0; g < groupCount; g++)
							{
								if (groupNoises[g] > 0.0F && this.groups.get(g).stormType())
								{
									columnStorm[ci] = true;
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
						if (regionMode && g != columnGroup[ci])
							continue;
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
		int center = xSpan / 2;
		int marked = 0;
		for (int dx = center - 4; dx < center + 4; dx++)
		{
			for (int dz = center - 4; dz < center + 4; dz++)
			{
				if (columnStorm[dx * zSpan + dz])
					marked++;
			}
		}
		outStormCoverage[0] = marked / 64.0F;

		outOpaqueCount[0] = opaqueWritten / CloudVertexFormat.BYTES_PER_INSTANCE;
		outTransparentCount[0] = transparentWritten / CloudVertexFormat.BYTES_PER_INSTANCE_ALPHA;
		return new ByteBuffer[] { bound(opaqueBuffer, opaqueWritten), bound(transparentBuffer, transparentWritten) };
	}

	/** Copies a scratch buffer (bytes 0..size) into a larger buffer. */
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

	/** Absolute grid Y of the current volume base; noise Y is sampled relative to it. */
	private int yBase;

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
		// y arrives in ABSOLUTE grid units; the layer ranges and the noise Y are
		// relative to the volume base (camera-anchored, see generate()).
		int ly = y - this.yBase;
		if (ly < layer.heightOffset() || ly > layer.heightOffset() + layer.height() - 1)
			return -10000.0F;

		float sx = x / layer.scaleX();
		float sy = ly / layer.scaleY();
		float sz = z / layer.scaleZ();
		float px = sx + scrollX / layer.scaleX();
		float py = sy + scrollY / layer.scaleY();
		float pz = sz + scrollZ / layer.scaleZ();

		float noise = PsrdNoise.noise(px, py, pz, TILE_PERIOD_X, TILE_PERIOD_Y, TILE_PERIOD_Z, wiggle, gradient)
				* layer.valueScale() + layer.valueOffset();

		float heightDelta = ly - layer.heightOffset();
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
		// its center is (x + 0.5) * scale.
		float cx = (x + 0.5F) * scale, cy = (y + 0.5F) * scale, cz = (z + 0.5F) * scale;
		int written = 0;

		// Face order matches the shader: -X=0, +X=1, -Y=2, +Y=3, -Z=4, +Z=5.
		// Neighbor validity is checked at ADJACENT cells (x +/- 1), not +/- one grid
		// scale (that culls against the wrong cell and leaves coincident faces).
		written += this.emitFaceIfVisible(buffer, offset + written, 0, cx, cy, cz, radius, brightness, this.isValid(x - 1, y, z, scale, scrollX, scrollY, scrollZ, wiggle));
		written += this.emitFaceIfVisible(buffer, offset + written, 1, cx, cy, cz, radius, brightness, this.isValid(x + 1, y, z, scale, scrollX, scrollY, scrollZ, wiggle));
		written += this.emitFaceIfVisible(buffer, offset + written, 2, cx, cy, cz, radius, brightness, this.isValid(x, y - 1, z, scale, scrollX, scrollY, scrollZ, wiggle));
		written += this.emitFaceIfVisible(buffer, offset + written, 3, cx, cy, cz, radius, brightness, this.isValid(x, y + 1, z, scale, scrollX, scrollY, scrollZ, wiggle));
		written += this.emitFaceIfVisible(buffer, offset + written, 4, cx, cy, cz, radius, brightness, this.isValid(x, y, z - 1, scale, scrollX, scrollY, scrollZ, wiggle));
		written += this.emitFaceIfVisible(buffer, offset + written, 5, cx, cy, cz, radius, brightness, this.isValid(x, y, z + 1, scale, scrollX, scrollY, scrollZ, wiggle));
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

	/** Opaque cube with region-aware face culling (the neighbor must be in the same formation and still cloud). */
	private int emitVisibleFacesRegion(ByteBuffer buffer, int offset, int x, int y, int z, float scale, float brightness,
			int x0, int y0, int z0, int x1, int y1, int z1, int[] columnGroup, float[] columnFade,
			float s, float scrollX, float scrollY, float scrollZ, float wiggle, float[] gradient)
	{
		float radius = scale / 2.0F;
		float cx = (x + 0.5F) * scale, cy = (y + 0.5F) * scale, cz = (z + 0.5F) * scale;
		int gi = columnGroup[(x - x0) * (z1 - z0) + (z - z0)];
		int written = 0;
		written += this.emitRegionFace(buffer, offset + written, 0, cx, cy, cz, radius, brightness, x - 1, y, z, gi, x0, y0, z0, x1, y1, z1, columnGroup, columnFade, s, scrollX, scrollY, scrollZ, wiggle, gradient);
		written += this.emitRegionFace(buffer, offset + written, 1, cx, cy, cz, radius, brightness, x + 1, y, z, gi, x0, y0, z0, x1, y1, z1, columnGroup, columnFade, s, scrollX, scrollY, scrollZ, wiggle, gradient);
		written += this.emitRegionFace(buffer, offset + written, 2, cx, cy, cz, radius, brightness, x, y - 1, z, gi, x0, y0, z0, x1, y1, z1, columnGroup, columnFade, s, scrollX, scrollY, scrollZ, wiggle, gradient);
		written += this.emitRegionFace(buffer, offset + written, 3, cx, cy, cz, radius, brightness, x, y + 1, z, gi, x0, y0, z0, x1, y1, z1, columnGroup, columnFade, s, scrollX, scrollY, scrollZ, wiggle, gradient);
		written += this.emitRegionFace(buffer, offset + written, 4, cx, cy, cz, radius, brightness, x, y, z - 1, gi, x0, y0, z0, x1, y1, z1, columnGroup, columnFade, s, scrollX, scrollY, scrollZ, wiggle, gradient);
		written += this.emitRegionFace(buffer, offset + written, 5, cx, cy, cz, radius, brightness, x, y, z + 1, gi, x0, y0, z0, x1, y1, z1, columnGroup, columnFade, s, scrollX, scrollY, scrollZ, wiggle, gradient);
		return written;
	}

	private int emitRegionFace(ByteBuffer buffer, int offset, int side, float cx, float cy, float cz, float radius, float brightness,
			int nx, int ny, int nz, int gi, int x0, int y0, int z0, int x1, int y1, int z1,
			int[] columnGroup, float[] columnFade, float s, float scrollX, float scrollY, float scrollZ, float wiggle, float[] gradient)
	{
		if (this.isValidRegion(nx, ny, nz, gi, x0, y0, z0, x1, y1, z1, columnGroup, columnFade, s, scrollX, scrollY, scrollZ, wiggle, gradient))
			return 0;
		buffer.putFloat(offset, side);
		buffer.putFloat(offset + 4, cx);
		buffer.putFloat(offset + 8, cy);
		buffer.putFloat(offset + 12, cz);
		buffer.putFloat(offset + 16, radius);
		buffer.putFloat(offset + 20, brightness);
		return CloudVertexFormat.BYTES_PER_INSTANCE;
	}

	/**
	 * Region-aware neighbor validity (port of isPosValid with the region/fade applied):
	 * in-band, inside the same formation, and above the masked noise threshold.
	 */
	private boolean isValidRegion(int x, int y, int z, int gi, int x0, int y0, int z0, int x1, int y1, int z1,
			int[] columnGroup, float[] columnFade, float scale, float scrollX, float scrollY, float scrollZ, float wiggle, float[] gradient)
	{
		if (x < x0 || x >= x1 || y < y0 || y >= y1 || z < z0 || z >= z1)
			return false;
		int i = (x - x0) * (z1 - z0) + (z - z0);
		if (columnGroup[i] != gi)
			return false;
		float noise = sampleGroup(this.groups.get(gi), x, y, z, scale, scrollX, scrollY, scrollZ, wiggle, gradient);
		return noise + columnFade[i] > 0.0F;
	}

	/** Emits all six faces of a transparent voxel (port of createTransparentCube), returning bytes written. */
	private int emitTransparentCube(ByteBuffer buffer, int offset, int x, int y, int z, float scale, float brightness, float alpha)
	{
		float radius = scale / 2.0F;
		float cx = (x + 0.5F) * scale, cy = (y + 0.5F) * scale, cz = (z + 0.5F) * scale;
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

	/** Whether a position is inside the cloud (infinite-field mode; port of isPosValid, no region/fade). */
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

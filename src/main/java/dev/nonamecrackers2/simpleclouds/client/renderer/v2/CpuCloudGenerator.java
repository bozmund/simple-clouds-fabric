package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

import dev.nonamecrackers2.simpleclouds.client.noise.PsrdNoise;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
	private static final Logger LOGGER = LogManager.getLogger("simpleclouds/CpuGenerator");
	private static long lastYRangeLog = 0L; // step-1/8 proof log (throttled ~every 5 s)

	/** Step 5: the original's TransparencyDistance gate (cube_mesh.comp: default
	 *  {@code maxRadius / 2} cloud units; the uniform is set from the mesh generator,
	 *  whose default is exactly that). Transparent edge cubes are only generated
	 *  inside this radius around the camera — the outer half of the field is fogged
	 *  away by the 2560..10240 fog anyway, so this also saves work. */
	public static final float TRANSPARENCY_DISTANCE = 640.0F; // HIGH: 1280/2 cloud units

	/** A single noise layer (mirrors the GLSL NoiseLayer struct / AbstractNoiseSettings.Param). */
	public record NoiseLayer(float height, float valueOffset, float scaleX, float scaleY, float scaleZ,
			float fadeDistance, float heightOffset, float valueScale)
	{
	}

	/** All noise layers of one cloud type plus its transparency fade (a compute-shader LayerGroup). */
	/**
	 * One cloud type's render group (port of the original's LayerGroup SSBO entry).
	 *
	 * Step 8 (lighting/darkness): the per-cube brightness is computed from the
	 * type's storm fields (cube_mesh.comp, TYPE==1 block):
	 * <pre>
	 *   storminess = clamp(group.Storminess + fade * 0.1, 0, 1)
	 *   brightness = clamp(1.0 - storminess * (1.0 - clamp((y - group.StormStart)
	 *                 / group.StormFadeDistance, 0, 1)), 0, 1)
	 * </pre>
	 * where {@code y} is the cube's grid Y (cloud units above the volume base).
	 * Stormy types (cumulonimbus/nimbostratus/stratus) darken toward their base;
	 * fair-weather types (cumulus/itty_bitty) stay bright. This is the "not
	 * uniformly white" shading of the 1.20.1 mod (the light directions are static
	 * (0,0,0) even in the original, so per-face normal lighting — {@code cubeNormals},
	 * default off — is a separate, weaker effect).
	 */
	public record CloudLayerGroup(List<NoiseLayer> layers, float transparencyFade, boolean stormType,
			float storminess, float stormStart, float stormFadeDistance)
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
	 * Grows a generation buffer when a chunk turns out denser than the borrow. The
	 * implementation releases {@code current} and returns a buffer of at least
	 * {@code minCapacity} bytes containing the first {@code written} bytes of it.
	 * (A1: the render-side implementation does this through the {@link ChunkBufferPool}.)
	 */
	@FunctionalInterface
	public interface BufferGrower
	{
		ByteBuffer grow(ByteBuffer current, int minCapacity, int written);
	}

	/**
	 * Generates per-instance cloud data for the voxel grid [x0..x1) x [y0..y1) x [z0..z1)
	 * at the given scale/scroll, writing into the caller-provided buffers.
	 *
	 * A1 (VISUAL-PARITY-PLAN addendum): NO direct allocation on this path. The caller
	 * borrows {@code opaqueOut} / {@code transparentOut} from a {@link ChunkBufferPool},
	 * and hands them back after uploading the result. When a chunk is denser than the
	 * borrow, {@code grower} swaps in a bigger pooled buffer (rare: the pool reuses
	 * high-water buffers, so a stable sky never allocates again).
	 *
	 * @return the FINAL buffers (the grower may have swapped them for bigger ones):
	 *         {@code [opaque, transparent]} bound to the bytes written.
	 *
	 * @param cameraGridY the camera's Y in grid units; used for the storm-coverage metric
	 * @param camCloudXZ the camera's XZ in cloud units ({@code [x, z]}); step 5:
	 *                    the original's TransparencyDistance gate for edge cubes
	 * @param outStormCoverage [0] = fraction of the 8x8 columns around the camera center
	 *                         that contain at least one opaque storm-type cell above the camera
	 */
	public ByteBuffer[] generate(int x0, int y0, int z0, int x1, int y1, int z1, float scale, float scrollX, float scrollY, float scrollZ, float wiggle, int lodScale, float worldBaseY, ByteBuffer opaqueOut, ByteBuffer transparentOut, BufferGrower grower, float[] outOpaqueCount, float[] outTransparentCount, int cameraGridY, float[] camCloudXZ, float[] outStormCoverage)
	{
		// Parity with 1.20.1 (VISUAL-PARITY-PLAN step 1): the cloud volume is anchored
		// at WORLD Y = cloudHeight (passed in as worldBaseY), NEVER relative to the
		// camera. The grid spans y0..y1 cloud units above that base; layer heights
		// and the noise Y are relative to the volume base (y0). x/z stay world-fixed.
		// (The old camY-anchored volume put the stratus at sea level and made the
		// clouds track the player's altitude -- the two headline parity bugs.)
		this.yBase = y0;
		this.worldBaseY = worldBaseY;
		// LOD (step 2): the grid steps by lodScale CLOUD UNITS and each cube is
		// lodScale cloud units wide (radius lodScale/2), like the original compute
		// shader (x = id*Scale + RenderOffset, createCube radius Scale/2).
		int xSpan = x1 - x0, ySpan = y1 - y0, zSpan = z1 - z0;
		int xCells = xSpan / lodScale, yCells = ySpan / lodScale, zCells = zSpan / lodScale;
		int cells = xCells * yCells * zCells;
		// A1: the buffers are borrowed by the caller (pool) and grown in place only
		// when the chunk outgrows the borrow; nothing here allocates.
		int opaqueWritten = 0;
		int transparentWritten = 0;
		float[] gradient = this.gradient;
		// Step 1 proof: track the min/max WORLD Y of emitted opaque cubes so the
		// anchoring (world Y = cloudHeight + 8*y) can be verified in the log.
		float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
		// Step 8 proof: track the min/max brightness of emitted opaque cubes so the
		// per-cube storm shading (not uniformly 1.0) can be verified in the log.
		float minBright = 1.0F, maxBright = 0.0F;
		int groupCount = this.groups.size();
		float[] groupNoises = new float[groupCount];
		boolean[] columnStorm = new boolean[xCells * zCells];

		// Region mode: precompute the per-column formation mask (port of cloud_regions.comp).
		// Regions are 2D, so this is xz-sized, not volume-sized.
		boolean regionMode = !this.regions.isEmpty();
		int[] columnGroup = regionMode ? new int[xCells * zCells] : null;
		float[] columnFade = regionMode ? new float[xCells * zCells] : null;
		if (regionMode)
		{
			Arrays.fill(columnGroup, -1);
			float edge = 1.0F / REGION_EDGE_FADE_FACTOR;
			for (int x = x0; x < x1; x += lodScale)
			{
				for (int z = z0; z < z1; z += lodScale)
				{
					// Cloud units (1 unit = CLOUD_SCALE = 8 world blocks): the region
					// positions/radii and the noise coordinates share this space, and the
					// grid cell id at scale=8 is exactly a cloud-unit coordinate.
					float wx = x + lodScale * 0.5F, wz = z + lodScale * 0.5F;
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
					int i = ((x - x0) / lodScale) * zCells + ((z - z0) / lodScale);
					columnGroup[i] = best;
					// cube_mesh.comp: fade = -5 * (1 - g)^10
					columnFade[i] = best < 0 ? 0.0F : -5.0F * (float) Math.pow(1.0F - bestG, 10.0);
				}
			}
		}

		for (int x = x0; x < x1; x += lodScale)
		{
			for (int y = y0; y < y1; y += lodScale)
			{
				for (int z = z0; z < z1; z += lodScale)
				{
					int ci = ((x - x0) / lodScale) * zCells + ((z - z0) / lodScale);
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

					// Step 8 (lighting/darkness): per-cube brightness from the owning
					// group's storm fields (original cube_mesh.comp, TYPE==1 block).
					// Region mode: the cell's formation group; legacy infinite field:
					// group 0. Stormy types darken toward their base; fair-weather
					// types stay bright — the "not uniformly white" 1.20.1 look.
					int ownGroup = regionMode ? columnGroup[ci] : 0;
					float fade0 = regionMode ? columnFade[ci] : 0.0F;
					float brightness = 1.0F;
					if (stormShading && ownGroup >= 0 && ownGroup < groupCount)
					{
						CloudLayerGroup g0 = this.groups.get(ownGroup);
						float storminess = clamp(g0.storminess() + fade0 * 0.1F, 0.0F, 1.0F);
						float above = g0.stormFadeDistance() > 0.0F
								? clamp((y - g0.stormStart()) / g0.stormFadeDistance(), 0.0F, 1.0F)
							: 1.0F;
						brightness = clamp(1.0F - storminess * (1.0F - above), 0.0F, 1.0F);
					}
					if (anyOpaque)
					{
						float cubeY = (y + lodScale * 0.5F) * scale + worldBaseY;
						if (cubeY < minY) minY = cubeY;
						if (cubeY > maxY) maxY = cubeY;
						if (brightness < minBright) minBright = brightness;
						if (brightness > maxBright) maxBright = brightness;
						int needed = 6 * CloudVertexFormat.BYTES_PER_INSTANCE;
						if (opaqueWritten + needed > opaqueOut.capacity())
							opaqueOut = grower.grow(opaqueOut, opaqueWritten + needed, opaqueWritten);
						// Opaque cube (port of createCube): one per cell even when several
						// groups are opaque (identical geometry; the original emitted one
						// cube per group, which just overlapped).
						opaqueWritten += regionMode
								? emitVisibleFacesRegion(opaqueOut, opaqueWritten, x, y, z, scale, lodScale, brightness, x0, y0, z0, x1, y1, z1, columnGroup, columnFade, scale, scrollX, scrollY, scrollZ, wiggle, gradient)
								: emitVisibleFaces(opaqueOut, opaqueWritten, x, y, z, scale, lodScale, brightness, scrollX, scrollY, scrollZ, wiggle);

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
					// Step 5: the original's TransparencyDistance gate (cube_mesh.comp):
					// transparent edge cubes are only generated within TransparencyDistance
					// (default maxRadius/2) cloud units of the camera origin.
					float tx = x + lodScale * 0.5F - camCloudXZ[0];
					float tz = z + lodScale * 0.5F - camCloudXZ[1];
					boolean inTranspDist = tx * tx + tz * tz <= TRANSPARENCY_DISTANCE * TRANSPARENCY_DISTANCE;
					for (int g = 0; g < groupCount; g++)
					{
						if (regionMode && g != columnGroup[ci])
							continue;
						if (groupNoises[g] > 0.0F)
							continue;
						CloudLayerGroup group = this.groups.get(g);
						float fade = group.transparencyFade();
						float noise = groupNoises[g];
						if (inTranspDist && fade > 0.01F && noise > -fade)
						{
							float alpha = (noise + fade) / fade;
							int needed = 6 * CloudVertexFormat.BYTES_PER_INSTANCE_ALPHA;
							if (transparentWritten + needed > transparentOut.capacity())
								transparentOut = grower.grow(transparentOut, transparentWritten + needed, transparentWritten);
							transparentWritten += emitTransparentCube(transparentOut, transparentWritten, x, y, z, scale, lodScale, brightness, alpha);
						}
					}
				}
			}
		}

		// Fraction of the 8x8 central columns (around the camera) with storm above.
		int center = xCells / 2;
		int marked = 0;
		for (int dx = center - 4; dx < center + 4; dx++)
		{
			if (dx < 0 || dx >= xCells) continue;
			for (int dz = center - 4; dz < center + 4; dz++)
			{
				if (dz < 0 || dz >= zCells) continue;
				if (columnStorm[dx * zCells + dz])
					marked++;
			}
		}
		outStormCoverage[0] = marked / 64.0F;

		outOpaqueCount[0] = opaqueWritten / CloudVertexFormat.BYTES_PER_INSTANCE;
		outTransparentCount[0] = transparentWritten / CloudVertexFormat.BYTES_PER_INSTANCE_ALPHA;
		// Step 1/8 proof log (throttled ~every 5 s): the generated cloud volume must
		// sit at world Y = cloudHeight + 8*y (>= ~128 by default), never at sea level,
		// and the per-cube brightness (step 8) must vary (not all 1.0) when stormy
		// types are present. Throttled (not one-shot) so chunks generated after the
		// DevShot tokens take effect are also sampled.
		if (opaqueWritten > 0 && devProofLogging)
		{
			long now = System.nanoTime();
			if (now - lastYRangeLog >= 5_000_000_000L)
			{
				lastYRangeLog = now;
				LOGGER.info("Simple Clouds clouds: generated Y range {}..{} (worldBaseY={}, {} opaque instances, brightness {}..{})",
						String.format(java.util.Locale.ROOT, "%.1f", minY), String.format(java.util.Locale.ROOT, "%.1f", maxY),
						String.format(java.util.Locale.ROOT, "%.1f", worldBaseY), (int) outOpaqueCount[0],
						String.format(java.util.Locale.ROOT, "%.2f", minBright), String.format(java.util.Locale.ROOT, "%.2f", maxBright));
			}
		}
		// Hand the buffers back bound to the bytes written (the caller uploads /
		// copies [0, limit) and then releases them to the pool). The returned
		// buffers are the FINAL ones (grower swaps are invisible to the argument
		// references).
		opaqueOut.position(0);
		opaqueOut.limit(opaqueWritten);
		transparentOut.position(0);
		transparentOut.limit(transparentWritten);
		return new ByteBuffer[] { opaqueOut, transparentOut };
	}

	/** Step 8 diagnostic (DevShot FLAT): false = force brightness 1.0 (no storm
	 *  shading) for an A/B comparison. Default true. */
	public static volatile boolean stormShading = true;

	/** Step 1/8 proof log: only when a DevShot run is active (DevShot sets it),
	 *  so the shipped mod's chunk workers stay quiet. */
	public static volatile boolean devProofLogging = false;

	/** Reused gradient scratch (A1: one generator per worker thread, no per-cell allocation). */
	private final float[] gradient = new float[3];

	/** Absolute grid Y of the current volume base; noise Y is sampled relative to it. */
	private int yBase;
	/** World Y (blocks) of cloud-unit 0: the cloudHeight anchor (step 1). Added to
	 *  every emitted vertex Y. 0 in the preview screen's own coordinate space. */
	private float worldBaseY;

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
	private int emitVisibleFaces(ByteBuffer buffer, int offset, int x, int y, int z, float scale, int lodScale, float brightness,
			float scrollX, float scrollY, float scrollZ, float wiggle)
	{
		float radius = lodScale * scale / 2.0F;
		// SidePos must be in WORLD coordinates (the shader adds it to the view-space
		// position untransformed). Grid cell (x, y, z) spans cloud-unit
		// [x*scale, (x+1)*scale); its center is (x + 0.5) * scale, and the cloud-unit
		// Y 0 is world Y = worldBaseY (the cloudHeight anchor, step 1).
		float cx = (x + lodScale * 0.5F) * scale, cy = (y + lodScale * 0.5F) * scale + this.worldBaseY, cz = (z + lodScale * 0.5F) * scale;
		int written = 0;

		// Face order matches the shader: -X=0, +X=1, -Y=2, +Y=3, -Z=4, +Z=5.
		// Neighbor validity is checked at the adjacent LOD cell (x +/- lodScale), the
		// next cube in the tiling (that is what is coincident with this face).
		written += this.emitFaceIfVisible(buffer, offset + written, 0, cx, cy, cz, radius, brightness, this.isValid(x - lodScale, y, z, scale, scrollX, scrollY, scrollZ, wiggle));
		written += this.emitFaceIfVisible(buffer, offset + written, 1, cx, cy, cz, radius, brightness, this.isValid(x + lodScale, y, z, scale, scrollX, scrollY, scrollZ, wiggle));
		written += this.emitFaceIfVisible(buffer, offset + written, 2, cx, cy, cz, radius, brightness, this.isValid(x, y - lodScale, z, scale, scrollX, scrollY, scrollZ, wiggle));
		written += this.emitFaceIfVisible(buffer, offset + written, 3, cx, cy, cz, radius, brightness, this.isValid(x, y + lodScale, z, scale, scrollX, scrollY, scrollZ, wiggle));
		written += this.emitFaceIfVisible(buffer, offset + written, 4, cx, cy, cz, radius, brightness, this.isValid(x, y, z - lodScale, scale, scrollX, scrollY, scrollZ, wiggle));
		written += this.emitFaceIfVisible(buffer, offset + written, 5, cx, cy, cz, radius, brightness, this.isValid(x, y, z + lodScale, scale, scrollX, scrollY, scrollZ, wiggle));
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
	private int emitVisibleFacesRegion(ByteBuffer buffer, int offset, int x, int y, int z, float scale, int lodScale, float brightness,
			int x0, int y0, int z0, int x1, int y1, int z1, int[] columnGroup, float[] columnFade,
			float s, float scrollX, float scrollY, float scrollZ, float wiggle, float[] gradient)
	{
		float radius = lodScale * scale / 2.0F;
		float cx = (x + lodScale * 0.5F) * scale, cy = (y + lodScale * 0.5F) * scale + this.worldBaseY, cz = (z + lodScale * 0.5F) * scale;
		// Cell-based index (matches the precompute + the spaced loop); the span-based
		// (x-x0)*(z1-z0) is only valid for lodScale==1.
		int gi = columnGroup[((x - x0) / lodScale) * ((z1 - z0) / lodScale) + ((z - z0) / lodScale)];
		int written = 0;
		written += this.emitRegionFace(buffer, offset + written, 0, cx, cy, cz, radius, brightness, x - lodScale, y, z, gi, x0, y0, z0, x1, y1, z1, columnGroup, columnFade, s, lodScale, scrollX, scrollY, scrollZ, wiggle, gradient);
		written += this.emitRegionFace(buffer, offset + written, 1, cx, cy, cz, radius, brightness, x + lodScale, y, z, gi, x0, y0, z0, x1, y1, z1, columnGroup, columnFade, s, lodScale, scrollX, scrollY, scrollZ, wiggle, gradient);
		written += this.emitRegionFace(buffer, offset + written, 2, cx, cy, cz, radius, brightness, x, y - lodScale, z, gi, x0, y0, z0, x1, y1, z1, columnGroup, columnFade, s, lodScale, scrollX, scrollY, scrollZ, wiggle, gradient);
		written += this.emitRegionFace(buffer, offset + written, 3, cx, cy, cz, radius, brightness, x, y + lodScale, z, gi, x0, y0, z0, x1, y1, z1, columnGroup, columnFade, s, lodScale, scrollX, scrollY, scrollZ, wiggle, gradient);
		written += this.emitRegionFace(buffer, offset + written, 4, cx, cy, cz, radius, brightness, x, y, z - lodScale, gi, x0, y0, z0, x1, y1, z1, columnGroup, columnFade, s, lodScale, scrollX, scrollY, scrollZ, wiggle, gradient);
		written += this.emitRegionFace(buffer, offset + written, 5, cx, cy, cz, radius, brightness, x, y, z + lodScale, gi, x0, y0, z0, x1, y1, z1, columnGroup, columnFade, s, lodScale, scrollX, scrollY, scrollZ, wiggle, gradient);
		return written;
	}

	private int emitRegionFace(ByteBuffer buffer, int offset, int side, float cx, float cy, float cz, float radius, float brightness,
			int nx, int ny, int nz, int gi, int x0, int y0, int z0, int x1, int y1, int z1,
			int[] columnGroup, float[] columnFade, float s, int lodScale, float scrollX, float scrollY, float scrollZ, float wiggle, float[] gradient)
	{
		if (this.isValidRegion(nx, ny, nz, gi, x0, y0, z0, x1, y1, z1, columnGroup, columnFade, s, lodScale, scrollX, scrollY, scrollZ, wiggle, gradient))
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
			int[] columnGroup, float[] columnFade, float scale, int lodScale, float scrollX, float scrollY, float scrollZ, float wiggle, float[] gradient)
	{
		if (x < x0 || x >= x1 || y < y0 || y >= y1 || z < z0 || z >= z1)
			return false;
		int zCells = (z1 - z0) / lodScale;
		int i = ((x - x0) / lodScale) * zCells + ((z - z0) / lodScale);
		if (columnGroup[i] != gi)
			return false;
		float noise = sampleGroup(this.groups.get(gi), x, y, z, scale, scrollX, scrollY, scrollZ, wiggle, gradient);
		return noise + columnFade[i] > 0.0F;
	}

	/** Emits all six faces of a transparent voxel (port of createTransparentCube), returning bytes written. */
	private int emitTransparentCube(ByteBuffer buffer, int offset, int x, int y, int z, float scale, int lodScale, float brightness, float alpha)
	{
		float radius = lodScale * scale / 2.0F;
		float cx = (x + lodScale * 0.5F) * scale, cy = (y + lodScale * 0.5F) * scale + this.worldBaseY, cz = (z + lodScale * 0.5F) * scale;
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
		float[] gradient = this.gradient;
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

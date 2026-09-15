package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Plan item 3 (spatial storm fog): where storm-type cloud is overhead, around the camera.
 *
 * The old storm fog darkened the whole screen by one camera-wide scalar. The generator already
 * marks, per chunk column, whether storm-type cloud sits above the camera (the StormCoverage
 * metric); this map rasterizes every chunk's columns into a camera-centred CELLS x CELLS grid of
 * CELL_BLOCKS-wide cells (area overlap, so every LOD scale lands exactly), and the storm fog pass
 * ray-marches through it: only view rays that pass under storm cover darken.
 *
 * Uniform block written by {@link #writeUniform} (std140, matches core/storm_fog.fsh):
 * <pre>
 *   0  vec4 FogColor       rgb fog colour, a = strength
 *  16  vec4 FogParams      x = fog top (world Y), y = max march distance, z = density per block, w = debug mode
 *  32  vec4 CameraCell     xyz = camera world position, w = cell size (blocks)
 *  48  vec4 MapInfo        x, y = world X, Z of the map's corner, z = cells per side, w = bolt count
 *  64  vec4 BoltPos[8]     xyz = world position, w = light strength (0 when lightning light is off)
 * 192  vec4 BoltColor[8]   rgb, a = light radius (blocks)
 * 320  vec4 Coverage[256]  CELLS*CELLS floats, index = z * CELLS + x
 * </pre>
 */
public final class StormFogMap
{
	public static final int CELLS = 32;
	public static final float CELL_BLOCKS = 64.0F;
	public static final int MAX_BOLTS = 8;
	/** Farthest a fog ray marches (blocks); inside the map's CELLS/2 * CELL_BLOCKS half-width. */
	public static final float MAX_FOG_DISTANCE = 800.0F;
	/** How far a bolt lights the fog around it (blocks). */
	public static final float BOLT_RADIUS = 300.0F;
	public static final float FOG_DENSITY_PER_BLOCK = 0.004F;
	public static final int UBO_BYTES = 320 + CELLS * CELLS * 4;

	private final float[] coverage = new float[CELLS * CELLS];
	private float originX;
	private float originZ;
	private boolean valid;
	private boolean any;

	/** Centres the map on the camera (snapped to whole cells) and clears it. */
	public void begin(double camX, double camZ)
	{
		Arrays.fill(this.coverage, 0.0F);
		this.any = false;
		this.valid = Double.isFinite(camX) && Double.isFinite(camZ);
		if (!this.valid)
			return;
		this.originX = (float) (Math.floor(camX / CELL_BLOCKS) * CELL_BLOCKS - CELLS / 2 * CELL_BLOCKS);
		this.originZ = (float) (Math.floor(camZ / CELL_BLOCKS) * CELL_BLOCKS - CELLS / 2 * CELL_BLOCKS);
	}

	/**
	 * Adds one generated chunk: {@code columns[ix * zCells + iz] != 0} marks storm-type cloud above
	 * the camera in the column that covers columnBlocks x columnBlocks blocks from
	 * (worldX + ix * columnBlocks, worldZ + iz * columnBlocks).
	 */
	public void addChunk(byte[] columns, int xCells, int zCells, float worldX, float worldZ, float columnBlocks)
	{
		if (!this.valid || columns == null || columns.length < xCells * zCells || !(columnBlocks > 0.0F)
				|| !Float.isFinite(worldX) || !Float.isFinite(worldZ))
			return;
		float cellArea = CELL_BLOCKS * CELL_BLOCKS;
		for (int ix = 0; ix < xCells; ix++)
		{
			float x0 = worldX + ix * columnBlocks - this.originX, x1 = x0 + columnBlocks;
			if (x1 <= 0.0F || x0 >= CELLS * CELL_BLOCKS)
				continue;
			for (int iz = 0; iz < zCells; iz++)
			{
				if (columns[ix * zCells + iz] == 0)
					continue;
				float z0 = worldZ + iz * columnBlocks - this.originZ, z1 = z0 + columnBlocks;
				if (z1 <= 0.0F || z0 >= CELLS * CELL_BLOCKS)
					continue;
				int cx0 = Math.max(0, (int) Math.floor(x0 / CELL_BLOCKS)), cx1 = Math.min(CELLS - 1, (int) Math.ceil(x1 / CELL_BLOCKS) - 1);
				int cz0 = Math.max(0, (int) Math.floor(z0 / CELL_BLOCKS)), cz1 = Math.min(CELLS - 1, (int) Math.ceil(z1 / CELL_BLOCKS) - 1);
				for (int cx = cx0; cx <= cx1; cx++)
				{
					float ox = Math.min(x1, (cx + 1) * CELL_BLOCKS) - Math.max(x0, cx * CELL_BLOCKS);
					if (ox <= 0.0F)
						continue;
					for (int cz = cz0; cz <= cz1; cz++)
					{
						float oz = Math.min(z1, (cz + 1) * CELL_BLOCKS) - Math.max(z0, cz * CELL_BLOCKS);
						if (oz <= 0.0F)
							continue;
						this.coverage[cz * CELLS + cx] += ox * oz / cellArea;
						this.any = true;
					}
				}
			}
		}
	}

	/** Coverage 0..1 of cell (x, z); 0 outside the map. */
	public float get(int x, int z)
	{
		if (x < 0 || z < 0 || x >= CELLS || z >= CELLS)
			return 0.0F;
		return Math.min(1.0F, this.coverage[z * CELLS + x]);
	}

	public boolean hasCoverage()
	{
		return this.any;
	}

	public float originX()
	{
		return this.originX;
	}

	public float originZ()
	{
		return this.originZ;
	}

	/**
	 * Writes the StormFog uniform block. {@code bolts} holds {@code boltCount} entries of
	 * [x, y, z, strength, r, g, b, radius].
	 */
	public void writeUniform(ByteBuffer data, double camX, double camY, double camZ, float fogTop, float maxDistance,
			float[] bolts, int boltCount, int debugMode)
	{
		data.putFloat(0, 0.04F);
		data.putFloat(4, 0.045F);
		data.putFloat(8, 0.06F);
		data.putFloat(12, 1.0F);
		data.putFloat(16, fogTop);
		data.putFloat(20, maxDistance);
		data.putFloat(24, FOG_DENSITY_PER_BLOCK);
		data.putFloat(28, debugMode);
		data.putFloat(32, (float) camX);
		data.putFloat(36, (float) camY);
		data.putFloat(40, (float) camZ);
		data.putFloat(44, CELL_BLOCKS);
		data.putFloat(48, this.originX);
		data.putFloat(52, this.originZ);
		data.putFloat(56, CELLS);
		int count = Math.max(0, Math.min(boltCount, MAX_BOLTS));
		data.putFloat(60, count);
		for (int i = 0; i < MAX_BOLTS; i++)
		{
			for (int k = 0; k < 4; k++)
			{
				data.putFloat(64 + i * 16 + k * 4, i < count ? bolts[i * 8 + k] : 0.0F);
				data.putFloat(192 + i * 16 + k * 4, i < count ? bolts[i * 8 + 4 + k] : 0.0F);
			}
		}
		for (int j = 0; j < CELLS * CELLS; j++)
			data.putFloat(320 + j * 4, Math.min(1.0F, this.coverage[j]));
	}
}

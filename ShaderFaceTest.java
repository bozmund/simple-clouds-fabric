import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import dev.nonamecrackers2.simpleclouds.client.noise.PsrdNoise;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.CpuCloudGenerator;

/**
 * Shader face contract test (Phase 1, discriminating).
 *
 * The face contract ties four sources together:
 *   1. cloud_faces.glsl    applySideTransform(side) -- rotates the base quad (x=-1) to the face
 *   2. clouds.vsh          sideNormal(side)         -- face normal
 *   3. cube_mesh.comp      createFace(index K) + isPosValid(neighbor)  -- GPU culling direction
 *   4. CpuCloudGenerator   emit...Face(K) + isValid(neighbor)          -- CPU culling direction
 *
 * All four must agree on the same side-id <-> neighbor-direction mapping, the GLSL transform
 * must be a proper rotation landing on the vsh normal's plane, and the REAL generator output
 * must satisfy the culling invariants: exact cell-center positions, no opposite face pairs,
 * Y volume boundaries, and correct cross-chunk culling at the shared chunk border.
 */
public class ShaderFaceTest
{
	static final String SHADERS = "src/main/resources/assets/simpleclouds/shaders/";
	static final String GEN = "src/main/java/dev/nonamecrackers2/simpleclouds/client/renderer/v2/CpuCloudGenerator.java";
	static int checks = 0;

	static void require(boolean b, String label)
	{
		checks++;
		if (!b)
			throw new AssertionError(label);
	}

	// ------------------------------------------------------------------ GLSL parsing

	/** Parses one transform expression (`p` or `vec3(±p.x, ±p.y, ±p.z)`) into a 3x3 linear map. */
	static int[][] parseTransformExpr(String expr)
	{
		expr = expr.trim();
		int[][] mm = new int[3][3];
		if (expr.equals("p"))
		{
			for (int i = 0; i < 3; i++)
				mm[i][i] = 1;
			return mm;
		}
		Matcher vM = Pattern.compile("vec3\\s*\\((.+?)\\)").matcher(expr);
		require(vM.find(), "unparseable transform expression: " + expr);
		String[] comps = vM.group(1).split(",");
		require(comps.length == 3, "transform expression needs 3 components: " + expr);
		for (int i = 0; i < 3; i++)
		{
			Matcher cM = Pattern.compile("^\\s*(-?)\\s*p\\s*\\.\\s*([xyz])\\s*$").matcher(comps[i]);
			require(cM.matches(), "unparseable component '" + comps[i].trim() + "' in " + expr);
			int sign = cM.group(1).equals("-") ? -1 : 1;
			int j = "xyz".indexOf(cM.group(2));
			mm[i][j] = sign;
		}
		return mm;
	}

	/** Parses `if (side == N) return <expr>;` lines (plus the bare final return) into 3x3 linear maps. */
	static int[][][] parseTransforms(String src)
	{
		int[][][] m = new int[6][][];
		int[][] bare = null;
		for (String line : src.split("\n"))
		{
			String t = line.trim();
			Matcher ifM = Pattern.compile("if\\s*\\(\\s*side\\s*==\\s*(\\d+)\\s*\\)\\s*return\\s+(.+?);").matcher(t);
			if (ifM.matches())
			{
				int s = Integer.parseInt(ifM.group(1));
				require(m[s] == null, "duplicate side " + s + " in cloud_faces.glsl");
				m[s] = parseTransformExpr(ifM.group(2));
				continue;
			}
			Matcher retM = Pattern.compile("^return\\s+(.+?);").matcher(t);
			if (retM.matches())
			{
				require(bare == null, "cloud_faces.glsl has more than one bare return");
				bare = parseTransformExpr(retM.group(1));
			}
		}
		if (bare != null)
		{
			int missing = -1, count = 0;
			for (int s = 0; s < 6; s++)
			{
				if (m[s] != null)
					count++;
				else
					missing = s;
			}
			require(count == 5 && missing == 5, "cloud_faces.glsl bare return is for side " + missing + " (expected 5)");
			m[5] = bare;
		}
		for (int s = 0; s < 6; s++)
			require(m[s] != null, "cloud_faces.glsl is missing side " + s);
		return m;
	}

	/** Parses the `vec3 sideNormal(int side)` function into normals per side. */
	static double[][] parseSideNormals(String src)
	{
		int start = src.indexOf("vec3 sideNormal(");
		require(start >= 0, "clouds.vsh: sideNormal() not found");
		int end = src.indexOf('}', start);
		String fn = src.substring(start, end);
		double[][] n = new double[6][];
		for (String line : fn.split("\n"))
		{
			Matcher m = Pattern.compile("return\\s+vec3\\s*\\(\\s*([-\\d.]+)\\s*,\\s*([-\\d.]+)\\s*,\\s*([-\\d.]+)\\s*\\);").matcher(line.trim());
			if (!m.find()) // lines may start with "if (side == N)"; matches() required the bare return only
				continue;
			double[] v = { Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)), Double.parseDouble(m.group(3)) };
			Matcher ifM = Pattern.compile("if\\s*\\(\\s*side\\s*==\\s*(\\d+)\\s*\\)").matcher(line);
			int s;
			if (ifM.find())
				s = Integer.parseInt(ifM.group(1));
			else
			{
				// Bare final return: only side 5 may use it, and it must be the only one missing.
				int missing = -1, count = 0;
				for (int i = 0; i < 6; i++)
				{
					if (n[i] != null)
						count++;
					else
						missing = i;
				}
				require(count == 5 && missing == 5, "clouds.vsh sideNormal: unexpected bare return, side " + missing);
				s = missing;
			}
			require(n[s] == null, "clouds.vsh: duplicate side " + s);
			n[s] = v;
		}
		for (int s = 0; s < 6; s++)
			require(n[s] != null, "clouds.vsh sideNormal is missing side " + s);
		return n;
	}

	// ------------------------------------------------------- source contract parsing

	/** Extracts the neighbor direction (axis + sign) from an expression like `x - lodScale`. */
	static int[] direction(String a, String b, String c)
	{
		int[] d = new int[3];
		Matcher ma = Pattern.compile("^\\s*([xyz])\\s*([-+])\\s*\\S+").matcher(a.trim());
		Matcher mb = Pattern.compile("^\\s*([xyz])\\s*([-+])\\s*\\S+").matcher(b.trim());
		Matcher mc = Pattern.compile("^\\s*([xyz])\\s*([-+])\\s*\\S+").matcher(c.trim());
		if (ma.matches())
			d["xyz".indexOf(ma.group(1))] = ma.group(2).equals("-") ? -1 : 1;
		else
			require(a.matches("[xyz]"), "unparseable neighbor x-expr: " + a);
		if (mb.matches())
			d["xyz".indexOf(mb.group(1))] = mb.group(2).equals("-") ? -1 : 1;
		else
			require(b.matches("[xyz]"), "unparseable neighbor y-expr: " + b);
		if (mc.matches())
			d["xyz".indexOf(mc.group(1))] = mc.group(2).equals("-") ? -1 : 1;
		else
			require(c.matches("[xyz]"), "unparseable neighbor z-expr: " + c);
		int sum = Math.abs(d[0]) + Math.abs(d[1]) + Math.abs(d[2]);
		require(sum == 1, "neighbor must differ in exactly one axis, got " + java.util.Arrays.toString(d));
		return d;
	}

	static int[][] parseCreateFaces(String src)
	{
		int[][] dirs = new int[6][];
		String[] lines = src.split("\n");
		for (int i = 0; i < lines.length; i++)
		{
			Matcher cf = Pattern.compile("createFace\\(center,\\s*cubeRadius,\\s*(\\d+)\\s*,\\s*brightness\\)").matcher(lines[i]);
			if (!cf.find())
				continue;
			int k = Integer.parseInt(cf.group(1));
			Matcher iv = Pattern.compile("isPosValid\\(([^,]+),\\s*([^,]+),\\s*([^,]+)").matcher(lines[i - 1]);
			require(iv.find(), "createFace " + k + " has no isPosValid neighbor check above it");
			require(dirs[k] == null, "cube_mesh.comp: duplicate face index " + k);
			dirs[k] = direction(iv.group(1), iv.group(2), iv.group(3));
		}
		for (int s = 0; s < 6; s++)
			require(dirs[s] != null, "cube_mesh.comp createFace is missing index " + s);
		return dirs;
	}

	static int[][] parseCpuFaces(String src, String emitKind)
	{
		int[][] dirs = new int[6][];
		Pattern p;
		Matcher[] groups;
		if (emitKind.equals("plain"))
			p = Pattern.compile("emitFaceIfVisible\\(buffer, offset \\+ written, (\\d+), cx, cy, cz, radius, brightness, this\\.isValid\\(([^,]+),\\s*([^,]+),\\s*([^,]+)");
		else
			p = Pattern.compile("emitRegionFace\\(buffer, offset \\+ written, (\\d+), cx, cy, cz, radius, brightness, ([^,]+),\\s*([^,]+),\\s*([^,]+), gi");
		for (String line : src.split("\n"))
		{
			Matcher m = p.matcher(line);
			if (m.find())
			{
				int k = Integer.parseInt(m.group(1));
				require(dirs[k] == null, "CpuCloudGenerator " + emitKind + ": duplicate side " + k);
				dirs[k] = direction(m.group(2), m.group(3), m.group(4));
			}
		}
		for (int s = 0; s < 6; s++)
			require(dirs[s] != null, "CpuCloudGenerator " + emitKind + " is missing side " + s);
		return dirs;
	}

	// ------------------------------------------------------------------ math checks

	static int det3(int[][] m)
	{
		return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
			- m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
			+ m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
	}

	static int[] mul(int[][] m, int[] v)
	{
		int[] r = new int[3];
		for (int i = 0; i < 3; i++)
			for (int j = 0; j < 3; j++)
				r[i] += m[i][j] * v[j];
		return r;
	}

	// ------------------------------------------------------------- behavioral checks

	/**
	 * Mirror of CpuCloudGenerator.sampleLayer for the single test layer
	 * NoiseLayer(height=16, valueOffset=3, scaleX=scaleY=scaleZ=16, fadeDistance=1,
	 * heightOffset=0, valueScale=1), yBase = 0, scroll = wiggle = 0. The r=10000 region
	 * gives columnFade = 0 everywhere in these chunks (d <= ~73 << radius - 200), so
	 * cell validity is exactly this value > 0.
	 */
	static float layerValue(int x, int y, int z)
	{
		int ly = y;
		if (ly < 0 || ly > 15)
			return -10000.0F;
		float noise = PsrdNoise.noise(x / 16.0F, ly / 16.0F, z / 16.0F, 32.0F, 64.0F, 32.0F, 0.0F, NOISE_GRADIENT)
				* 1.0F + 3.0F;
		float hd = ly;
		noise -= 1.0F - clamp01(hd / 1.0F);
		noise -= 1.0F - clamp01((16.0F - hd) / 1.0F);
		return noise;
	}

	static float clamp01(float v)
	{
		return v < 0.0F ? 0.0F : Math.min(v, 1.0F);
	}

	static final float[] NOISE_GRADIENT = { 0.0F, 0.0F, 0.0F };
	static final int[][] DIRS = { { -1, 0, 0 }, { 1, 0, 0 }, { 0, -1, 0 }, { 0, 1, 0 }, { 0, 0, -1 }, { 0, 0, 1 } };

	static long key(int x, int y, int z)
	{
		return ((long) x << 20) | ((long) y << 10) | z;
	}

	/**
	 * The exact expected face set: face (side, cell) exists iff the cell is valid and the
	 * neighbor in that side's direction is invalid (outside the volume band, or outside
	 * the formation/noise). Neighbors one cell beyond the chunk border are in-band (the
	 * halo) -- exactly the cross-chunk culling the seam bug broke.
	 */
	static BitSet expectedFaces(int x, int y, int z, int x0, int x1, int z0, int z1)
	{
		BitSet f = new BitSet(6);
		if (layerValue(x, y, z) <= 0.0F)
			return f;
		for (int s = 0; s < 6; s++)
		{
			int nx = x + DIRS[s][0], ny = y + DIRS[s][1], nz = z + DIRS[s][2];
			boolean outOfBand = nx < x0 - 1 || nx > x1 || ny < 0 || ny >= 16 || nz < z0 - 1 || nz > z1;
			if (outOfBand || layerValue(nx, ny, nz) <= 0.0F)
				f.set(s);
		}
		return f;
	}

	static Map<Long, BitSet> decode(ByteBuffer buf, int x0, int x1, int z0, int z1, float scale, float worldBaseY)
	{
		Map<Long, BitSet> faces = new TreeMap<>();
		for (int p = 0; p < buf.limit(); p += 24)
		{
			int side = (int) buf.getFloat(p);
			float cx = buf.getFloat(p + 4), cy = buf.getFloat(p + 8), cz = buf.getFloat(p + 12);
			require(side >= 0 && side < 6, "side id out of range: " + side);
			float fx = cx / scale - 0.5F, fy = (cy - worldBaseY) / scale - 0.5F, fz = cz / scale - 0.5F;
			int x = Math.round(fx), y = Math.round(fy), z = Math.round(fz);
			require((float) x == fx && (float) y == fy && (float) z == fz,
					"SidePos is not a cell center: (" + cx + "," + cy + "," + cz + ")");
			require(cx == (x + 0.5F) * scale && cy == (y + 0.5F) * scale + worldBaseY && cz == (z + 0.5F) * scale,
					"SidePos disagrees with the cell-center formula: (" + cx + "," + cy + "," + cz + ")");
			require(x >= x0 && x < x1 && y >= 0 && y < 16 && z >= z0 && z < z1,
					"face cell (" + x + "," + y + "," + z + ") outside the chunk");
			faces.computeIfAbsent(((long) x << 20) | ((long) y << 10) | z, q -> new BitSet(6)).set(side);
		}
		return faces;
	}

	static void invariants(Map<Long, BitSet> faces, String label)
	{
		for (Map.Entry<Long, BitSet> e : faces.entrySet())
		{
			int y = (int) ((e.getKey() >> 10) & 1023);
			BitSet s = e.getValue();
			require(!(s.get(0) && s.get(1)), label + ": cell has both -X and +X faces (interior wall)");
			require(!(s.get(2) && s.get(3)), label + ": cell has both -Y and +Y faces (interior wall)");
			require(!(s.get(4) && s.get(5)), label + ": cell has both -Z and +Z faces (interior wall)");
			if (s.get(2))
				require(y == 0, label + ": -Y face above the volume base (y=" + y + ")");
			if (s.get(3))
				require(y == 15, label + ": +Y face below the volume top (y=" + y + ")");
		}
	}

	public static void main(String[] args) throws Exception
	{
		String glsl = Files.readString(Path.of(SHADERS + "include/cloud_faces.glsl"));
		String vsh = Files.readString(Path.of(SHADERS + "core/clouds.vsh"));
		String comp = Files.readString(Path.of(SHADERS + "compute/cube_mesh.comp"));
		String gen = Files.readString(Path.of(GEN));

		int[][][] transforms = parseTransforms(glsl);
		double[][] normals = parseSideNormals(vsh);
		int[][] compDirs = parseCreateFaces(comp);
		int[][] cpuPlain = parseCpuFaces(gen, "plain");
		int[][] cpuRegion = parseCpuFaces(gen, "region");

		// Part 1: the GLSL transform is a rotation landing on the vsh normal's face plane.
		int[] baseNormal = { -1, 0, 0 };
		for (int s = 0; s < 6; s++)
		{
			require(det3(transforms[s]) == 1, "applySideTransform(" + s + ") is not a rotation (det=" + det3(transforms[s]) + ")");
			int[] dir = mul(transforms[s], baseNormal);
			require(Math.abs(dir[0]) + Math.abs(dir[1]) + Math.abs(dir[2]) == 1,
					"applySideTransform(" + s + ") base normal is not an axis vector");
			for (int i = 0; i < 3; i++)
				require(normals[s][i] == dir[i],
						"side " + s + ": transform plane normal " + java.util.Arrays.toString(dir)
								+ " disagrees with clouds.vsh sideNormal " + java.util.Arrays.toString(normals[s]));
			// Base quad corners (x=-1) must land exactly on the unit-cube face.
			int sumX = 0, sumY = 0, sumZ = 0, count = 0;
			for (int sy : new int[] { -1, 1 })
				for (int sz : new int[] { -1, 1 })
				{
					int[] c = mul(transforms[s], new int[] { -1, sy, sz });
					require(Math.abs(c[0]) == 1 && Math.abs(c[1]) == 1 && Math.abs(c[2]) == 1,
							"side " + s + ": corner leaves the unit cube");
					int dot = c[0] * dir[0] + c[1] * dir[1] + c[2] * dir[2];
					require(dot == 1, "side " + s + ": corner off the face plane");
					sumX += c[0];
					sumY += c[1];
					sumZ += c[2];
					count++;
				}
			require(count == 4, "side " + s + ": corner count");
			require(sumX / 4 == dir[0] && sumY / 4 == dir[1] && sumZ / 4 == dir[2],
					"side " + s + ": face center is not at the normal position");
		}

		// Part 2: GPU and CPU cull in the direction the shader draws.
		for (int s = 0; s < 6; s++)
		{
			int[] dir = mul(transforms[s], baseNormal);
			require(java.util.Arrays.equals(compDirs[s], dir),
					"side " + s + ": cube_mesh.comp culls " + java.util.Arrays.toString(compDirs[s])
							+ " but the shader draws " + java.util.Arrays.toString(dir));
			require(java.util.Arrays.equals(cpuPlain[s], dir),
					"side " + s + ": CpuCloudGenerator (infinite mode) culls " + java.util.Arrays.toString(cpuPlain[s])
							+ " but the shader draws " + java.util.Arrays.toString(dir));
			require(java.util.Arrays.equals(cpuRegion[s], dir),
					"side " + s + ": CpuCloudGenerator (region mode) culls " + java.util.Arrays.toString(cpuRegion[s])
							+ " but the shader draws " + java.util.Arrays.toString(dir));
		}

		// Part 3: the real generator output obeys the culling invariants, including the
		// cross-chunk border (the "+1 halo" that prevents 32-cell interior walls).
		var layer = new CpuCloudGenerator.NoiseLayer(16, 3, 16, 16, 16, 1, 0, 0);
		var genr = new CpuCloudGenerator(List.of(new CpuCloudGenerator.CloudLayerGroup(List.of(layer), 0, false, 0, 0, 1)));
		genr.setRegions(List.of(new CpuCloudGenerator.RegionMask(0, 0, 10000, 1, 0, 0, 1, 0)));
		float scale = 8F, baseY = 128F;
		ByteBuffer opA = ByteBuffer.allocateDirect(4 * 1024 * 1024).order(ByteOrder.nativeOrder());
		ByteBuffer opB = ByteBuffer.allocateDirect(4 * 1024 * 1024).order(ByteOrder.nativeOrder());
		ByteBuffer tr = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
		CpuCloudGenerator.BufferGrower noGrow = (b, n, w) -> { throw new AssertionError("unexpected growth"); };
		// Chunk B is directly adjacent to A across the x = 31|32 border (not diagonal),
		// so the border columns (31, y, z) | (32, y, z) belong to different chunks.
		genr.generate(0, 0, 0, 32, 16, 32, scale, 0, 0, 0, 0, 1, baseY, opA, tr, noGrow, new float[1], new float[1], 0, new float[2], new float[1]);
		genr.generate(32, 0, 0, 64, 16, 32, scale, 0, 0, 0, 0, 1, baseY, opB, tr, noGrow, new float[1], new float[1], 0, new float[2], new float[1]);

		Map<Long, BitSet> a = decode(opA, 0, 32, 0, 32, scale, baseY);
		Map<Long, BitSet> b = decode(opB, 32, 64, 0, 32, scale, baseY);
		invariants(a, "chunk A");
		invariants(b, "chunk B");

		// Exact match against the contract-derived expectation, per chunk.
		int[] expectedCount = { 0 }, borderCells = { 0 };
		checkChunk("chunk A", a, 0, 32, 0, 32, expectedCount, borderCells);
		checkChunk("chunk B", b, 32, 64, 0, 32, expectedCount, borderCells);
		require(expectedCount[0] > 0, "expected face set is empty: the noise field produced no cloud at all");
		System.out.println("PASS: " + checks + " checks (4-source face contract, GLSL rotation/plane math, "
				+ expectedCount + " exact face-set cells vs contract incl. " + borderCells + " cross-chunk border cells)");
	}

	static void checkChunk(String label, Map<Long, BitSet> emitted, int x0, int x1, int z0, int z1,
			int[] expectedCount, int[] borderCells)
	{
		for (int x = x0; x < x1; x++)
			for (int y = 0; y < 16; y++)
				for (int z = z0; z < z1; z++)
			{
				BitSet want = expectedFaces(x, y, z, x0, x1, z0, z1);
				if (want.isEmpty())
					continue;
				expectedCount[0]++;
				if (x == x0 || x == x1 - 1 || z == z0 || z == z1 - 1)
					borderCells[0]++;
				BitSet got = emitted.get(key(x, y, z));
				if (got == null)
					got = new BitSet(6);
				require(got.equals(want), label + " cell (" + x + "," + y + "," + z + "): emitted faces "
						+ faces(got) + " but the culling contract says " + faces(want));
			}
		// No emitted face may exist outside the expected set (decode bounds already checked the chunk).
		for (long k : emitted.keySet())
		{
			int x = (int) (k >> 20), y = (int) ((k >> 10) & 1023), z = (int) (k & 1023);
			if (!expectedFaces(x, y, z, x0, x1, z0, z1).isEmpty())
				continue;
			require(false, label + " cell (" + x + "," + y + "," + z + "): emitted faces "
					+ faces(emitted.get(k)) + " but the cell is not a cloud");
		}
	}

	static String faces(BitSet f)
	{
		StringBuilder sb = new StringBuilder("{");
		for (int s = 0; s < 6; s++)
			if (f.get(s))
			{
				if (sb.length() > 1)
					sb.append(' ');
				sb.append(s);
			}
		return sb.append('}').toString();
	}
}

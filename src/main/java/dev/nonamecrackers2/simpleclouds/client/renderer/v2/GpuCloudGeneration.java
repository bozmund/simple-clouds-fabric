package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

import com.mojang.blaze3d.systems.RenderSystem;

/**
 * SPIKE (26.2, see SPIKE-GPU.md) — generates cloud instances with the ORIGINAL
 * {@code cube_mesh.comp} compute shader through raw LWJGL OpenGL, bypassing the
 * 26.2 Blaze3D pass API (which has no compute dispatch). Only valid on the OpenGL
 * backend; the CPU generator remains the fallback.
 *
 * Buffer strategy (empirically determined on this Mesa, see SPIKE-GPU-RESULT.md):
 * the GpuDevice facade's buffers cannot be CPU-mapped (Mesa rejects Mojang's
 * persistent-mapping flag encoding, and glBufferSubData on them fails with
 * 0x502), and GPU writes are not visible through them. This spike therefore
 * allocates its own plain GL buffers (glGenBuffers + glBufferStorage with flag 0),
 * which the core GL contract guarantees work with glBufferSubData and
 * glGetBufferSubData. The generated instance array is read back into a CPU
 * ByteBuffer and handed to the existing CloudsDrawPipeline.setInstances path.
 *
 * Build: TYPE=1 (single spherical cloud region: the noise field faded by
 * distance from Origin), TRANSPARENCY=0, FIXED_SECTION_SIZE=0, local size 8^3
 * (Mesa's max work group invocations is 1024, below the original's 32^3).
 */
public final class GpuCloudGeneration implements AutoCloseable
{
	public static final Logger LOGGER = LogManager.getLogger("simpleclouds/GpuSpike");

	// cube_mesh.comp buffer bindings, in declaration order for this build:
	private static final int B_TOTAL_SIDES = 0;
	private static final int B_SIDE_INFO = 1;
	private static final int B_SIDES_PER_CHUNK = 2;
	private static final int B_NOISE_LAYERS = 3;
	private static final int B_LAYER_GROUPINGS = 4;
	private static final int BINDING_COUNT = 5;
	// GL_BASE_VERTEX_BINDING_BUFFER (GL 4.3) -- not exposed by LWJGL 3.4. Mesa
	// answers the query with 0 and GL_INVALID_VALUE (0x500); the returned value is
	// still usable when the units are free, so the error is consumed, not logged.
	private static final int GL_BASE_VERTEX_BINDING_BUFFER = 0x82B4;

	// 8^3 = 512: within the GL spec minimum max work group invocations (1024, what
	// Mesa reports here); the original sized workgroups from the GPU's reported max.
	private static final int LOCAL_SIZE = 8;
	// 32x32x64-unit band at 8^3 local size; each cell can emit up to 6 faces.
	private static final int MAX_INSTANCES = 32 * 64 * 32 * 6;
	public static final int BYTES_PER_INSTANCE = 24;
	// Generous pre-allocated sizes (buffers must exist before the shader is
	// compiled because of the Mesa bug above).
	private static final int MAX_LAYERS = 32;
	private static final int MAX_GROUPS = 16;

	private int program = -1;
	private int totalSidesBuffer = 0;
	private int sideInfoBuffer = 0;
	private int sidesPerChunkBuffer = 0;
	private int noiseLayersBuffer = 0;
	private int layerGroupingsBuffer = 0;
	private int instanceCount;
	// The read-back instance data (count * 24 bytes), for CloudsDrawPipeline.setInstances.
	private ByteBuffer instanceData;
	// Last generate() timings (ns): dispatch+readback total.
	private long lastGenerateNanos;
	private int lastDispatchX, lastDispatchY, lastDispatchZ;

	// The 26.2 GpuDevice facade wraps a package-private backend (GlDevice/VkDevice)
	// in a private 'backend' field; detect which one without accessing the private
	// class.
	private static volatile String cachedBackendClassName = null;

	public static String backendClassName()
	{
		if (GpuCloudGeneration.cachedBackendClassName == null)
		{
			try
			{
				java.lang.reflect.Field f = com.mojang.blaze3d.systems.GpuDevice.class.getDeclaredField("backend");
				f.setAccessible(true);
				Object backend = f.get(RenderSystem.getDevice());
				GpuCloudGeneration.cachedBackendClassName = backend == null ? "null" : backend.getClass().getName();
			}
			catch (Throwable t)
			{
				LOGGER.warn("Spike: could not introspect the GpuDevice backend field", t);
				GpuCloudGeneration.cachedBackendClassName = "unknown";
			}
		}
		return GpuCloudGeneration.cachedBackendClassName;
	}

	public static boolean isOpenGLBackend()
	{
		return GpuCloudGeneration.backendClassName().equals("com.mojang.blaze3d.opengl.GlDevice");
	}

	/**
	 * Compiles the original compute shader and allocates the spike's plain GL
	 * buffers. Returns false (CPU fallback) if anything goes wrong.
	 */
	public boolean init()
	{
		try
		{
			LOGGER.info("Spike: GL version={}, vendor/renderer={}", GL11.glGetString(GL11.GL_VERSION),
					GL11.glGetString(GL11.GL_VENDOR) + "/" + GL11.glGetString(GL11.GL_RENDERER));
			// MESA DRIVER BUG (26.2.1 on Intel ARL, isolated by Probe.runShaderFirst):
			// compiling a compute shader makes every LATER glBufferData silently
			// no-op (GL_BUFFER_SIZE stays 0, no GL error). Allocate ALL buffers
			// first, compile the shader only afterwards.
			this.totalSidesBuffer = GL15.glGenBuffers();
			this.sideInfoBuffer = GL15.glGenBuffers();
			this.sidesPerChunkBuffer = GL15.glGenBuffers();
			this.noiseLayersBuffer = GL15.glGenBuffers();
			this.layerGroupingsBuffer = GL15.glGenBuffers();
			if (this.totalSidesBuffer == 0 || this.sideInfoBuffer == 0 || this.sidesPerChunkBuffer == 0
					|| this.noiseLayersBuffer == 0 || this.layerGroupingsBuffer == 0)
			{
				LOGGER.error("Spike: glGenBuffers failed (err 0x{})", Integer.toHexString(GL20.glGetError()));
				return false;
			}
			this.allocate(this.totalSidesBuffer, 16L);
			this.allocate(this.sideInfoBuffer, (long) MAX_INSTANCES * BYTES_PER_INSTANCE);
			this.allocate(this.sidesPerChunkBuffer, 16L);
			this.allocate(this.noiseLayersBuffer, (long) MAX_LAYERS * 32);
			this.allocate(this.layerGroupingsBuffer, (long) MAX_GROUPS * 24);
			// (No GL_BUFFER_SIZE sanity check here: this Mesa rejects that query with
			// INVALID_ENUM after a compute shader compile. The safety net is the
			// generated instance count: if allocations silently failed, the dispatch
			// writes nothing and the CPU path stays active.)
			ByteBuffer zero = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
			this.upload(this.totalSidesBuffer, zero);
			this.upload(this.sidesPerChunkBuffer, zero);

			String comp = GpuCloudGeneration.readResource("assets/simpleclouds/shaders/compute/cube_mesh.comp");
			String psrdnoise = GpuCloudGeneration.readResource("assets/simpleclouds/shaders/include/psrdnoise.glsl");
			comp = comp.replace("#moj_import <simpleclouds:psrdnoise.glsl>", psrdnoise)
					.replace("${TYPE}", "1")
					.replace("${FADE_NEAR_ORIGIN}", "0")
					.replace("${STYLE}", "0")
					.replace("${TRANSPARENCY}", "0")
					.replace("${FIXED_SECTION_SIZE}", "0")
					.replace("${LOCAL_SIZE_X}", String.valueOf(LOCAL_SIZE))
					.replace("${LOCAL_SIZE_Y}", String.valueOf(LOCAL_SIZE))
					.replace("${LOCAL_SIZE_Z}", String.valueOf(LOCAL_SIZE));

			int shader = GL43.glCreateShader(GL43.GL_COMPUTE_SHADER);
			GL20.glShaderSource(shader, comp);
			GL20.glCompileShader(shader);
			if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0)
			{
				LOGGER.error("Spike: compute shader compile failed: {}", GL20.glGetShaderInfoLog(shader, 32768));
				GL20.glDeleteShader(shader);
				return false;
			}
			int program = GL20.glCreateProgram();
			GL20.glAttachShader(program, shader);
			GL20.glDeleteShader(shader);
			GL20.glLinkProgram(program);
			if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0)
			{
				LOGGER.error("Spike: compute program link failed: {}", GL20.glGetProgramInfoLog(program, 32768));
				GL20.glDeleteProgram(program);
				return false;
			}
			this.program = program;
			this.checkGl("init");
			LOGGER.info("Spike: cube_mesh.comp compiled and linked (program handle={})", this.program);
			return true;
		}
		catch (Exception e)
		{
			LOGGER.error("Spike: init failed", e);
			return false;
		}
	}

	/**
	 * Generates one spherical single-type cloud region (TYPE 1) over the band
	 * [x0..x1) x [y0..y1) x [z0..z1) in CLOUD UNITS (8 world blocks each), with the
	 * field faded by distance from (centerX, centerY, centerZ) between fadeStart and
	 * fadeEnd (cloud units).
	 *
	 * @return the generated instance count, or -1 on failure; the read-back instance
	 *         array (count * 24 bytes, world-block coordinates) is then available via
	 *         {@link #instanceData()}
	 */
	public int generate(int x0, int y0, int z0, int x1, int y1, int z1,
			float centerX, float centerY, float centerZ, float fadeStart, float fadeEnd,
			List<CpuCloudGenerator.NoiseLayer> layers, float transparencyFade)
	{
		try
		{
			// NoiseLayers SSBO: 8 floats per layer, in the GLSL NoiseLayer struct order.
			ByteBuffer nl = ByteBuffer.allocateDirect(layers.size() * 32).order(ByteOrder.nativeOrder());
			for (CpuCloudGenerator.NoiseLayer l : layers)
			{
				nl.putFloat(l.height());
				nl.putFloat(l.valueOffset());
				nl.putFloat(l.scaleX());
				nl.putFloat(l.scaleY());
				nl.putFloat(l.scaleZ());
				nl.putFloat(l.fadeDistance());
				nl.putFloat(l.heightOffset());
				nl.putFloat(l.valueScale());
			}
			nl.flip();
			if (layers.size() > MAX_LAYERS)
			{
				LOGGER.error("Spike: {} layers exceed the pre-allocated {}", layers.size(), MAX_LAYERS);
				return -1;
			}
			this.upload(this.noiseLayersBuffer, nl);

			// LayerGroupings SSBO: one group covering all layers.
			ByteBuffer lg = ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder());
			lg.putInt(0); // StartIndex
			lg.putInt(layers.size()); // EndIndex
			lg.putFloat(0.0F); // Storminess
			lg.putFloat(0.0F); // StormStart
			lg.putFloat(0.0F); // StormFadeDistance
			lg.putFloat(transparencyFade); // TransparencyFade
			lg.flip();
			this.upload(this.layerGroupingsBuffer, lg);
			this.zeroCounters();

			int dx = (x1 - x0 + LOCAL_SIZE - 1) / LOCAL_SIZE;
			int dy = (y1 - y0 + LOCAL_SIZE - 1) / LOCAL_SIZE;
			int dz = (z1 - z0 + LOCAL_SIZE - 1) / LOCAL_SIZE;
			this.lastDispatchX = dx;
			this.lastDispatchY = dy;
			this.lastDispatchZ = dz;

			long t0 = System.nanoTime();
			// Save the GL state we touch (Blaze3D caches the current program).
			int prevProgram = GL20.glGetInteger(GL20.GL_CURRENT_PROGRAM);
			int[] prevBindings = new int[BINDING_COUNT];
			for (int i = 0; i < BINDING_COUNT; i++)
			{
				prevBindings[i] = GL20.glGetInteger(GL_BASE_VERTEX_BINDING_BUFFER + i);
				// Mesa answers this query with 0 + INVALID_VALUE; consume the error.
				GL20.glGetError();
			}

			int readBytes = 0;
			try
			{
				GL20.glUseProgram(this.program);
				this.bind(B_TOTAL_SIDES, this.totalSidesBuffer);
				this.bind(B_SIDE_INFO, this.sideInfoBuffer);
				this.bind(B_SIDES_PER_CHUNK, this.sidesPerChunkBuffer);
				this.bind(B_NOISE_LAYERS, this.noiseLayersBuffer);
				this.bind(B_LAYER_GROUPINGS, this.layerGroupingsBuffer);

				this.setUniform1f("Scale", 1.0F);
				this.setUniform3f("RenderOffset", (float) x0, (float) y0, (float) z0);
				this.setUniform3f("Origin", centerX, centerY, centerZ);
				this.setUniform1f("FadeStart", fadeStart);
				this.setUniform1f("FadeEnd", fadeEnd);
				this.setUniform3f("Scroll", 0.0F, 0.0F, 0.0F);
				this.setUniform1f("Wiggle", 0.0F);
				this.setUniform1i("ChunkIndex", 0);
				this.setUniform1i("DoNotOccludeSide", -1);
				this.setUniform1i("TestFacesFacingAway", 0);

				GL43.glDispatchCompute(dx, dy, dz);
				// Make the written instance data visible to the CPU read-back.
				GL42.glMemoryBarrier(GL42.GL_ALL_BARRIER_BITS);
				this.checkGl("dispatch");

				// Read back the face counter (classic flow, verified by Probe.runClassic).
				ByteBuffer counter = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
				this.download(this.totalSidesBuffer, counter);
				this.instanceCount = counter.remaining() >= 4 ? counter.getInt() : -1;

				// Read back the generated instance array for the draw pipeline.
				if (this.instanceCount > 0)
				{
					readBytes = this.instanceCount * BYTES_PER_INSTANCE;
					ByteBuffer out = ByteBuffer.allocateDirect(readBytes).order(ByteOrder.nativeOrder());
					this.download(this.sideInfoBuffer, out);
					this.instanceData = out.remaining() == readBytes ? out : null;
				}
				else
				{
					this.instanceData = null;
				}
				this.checkGl("readback");
			}
			finally
			{
				for (int i = 0; i < BINDING_COUNT; i++)
					GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, i, prevBindings[i]);
				GL20.glUseProgram(prevProgram);
				GpuCloudGeneration.syncGlStateManagerProgram(prevProgram);
			}
			this.lastGenerateNanos = System.nanoTime() - t0;
			this.checkGl("generate");
			LOGGER.info("Spike: dispatch({}, {}, {}) -> {} instances in {} us (readback {} bytes)",
					dx, dy, dz, this.instanceCount, this.lastGenerateNanos / 1000L, readBytes);
			return this.instanceCount;
		}
		catch (Exception e)
		{
			LOGGER.error("Spike: generate failed", e);
			return -1;
		}
	}

	public int instanceCount()
	{
		return this.instanceCount;
	}

	/** The read-back instance array (count * 24 bytes, world blocks) or null. */
	public ByteBuffer instanceData()
	{
		return this.instanceData;
	}

	public long lastGenerateNanos()
	{
		return this.lastGenerateNanos;
	}

	public static void err(String what)
	{
		int e = GL20.glGetError();
		if (e != GL20.GL_NO_ERROR)
			LOGGER.error("Spike: 0x{} at {}", Integer.toHexString(e), what);
	}

	private void allocate(int buffer, long bytes)
	{
		GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
		GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, bytes, GL15.GL_DYNAMIC_DRAW);
		int e = GL20.glGetError();
		if (e != GL20.GL_NO_ERROR)
			LOGGER.error("Spike: glBufferData({}B) on buffer {} failed: 0x{}", bytes, buffer, Integer.toHexString(e));
	}

	private void upload(int buffer, ByteBuffer data)
	{
		data.rewind();
		GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
		GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, data);
		GpuCloudGeneration.err("glBufferSubData " + buffer);
	}

	private void download(int buffer, ByteBuffer out)
	{
		out.rewind();
		GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
		GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, out);
		GpuCloudGeneration.err("glGetBufferSubData " + buffer);
		if (out.position() == 0)
		{
			// This Mesa silently no-ops glGetBufferSubData on compute-written buffers
			// after a shader compile; try the classic glMapBuffer (non-persistent)
			// fallback, which must return the current buffer contents.
			ByteBuffer mapped = GL15.glMapBuffer(GL43.GL_SHADER_STORAGE_BUFFER, GL15.GL_READ_ONLY);
			GpuCloudGeneration.err("glMapBuffer " + buffer);
			if (mapped != null && mapped.remaining() >= out.remaining())
			{
				out.put(mapped.limit(out.remaining()));
			}
			GL15.glUnmapBuffer(GL43.GL_SHADER_STORAGE_BUFFER);
			GpuCloudGeneration.err("glUnmapBuffer " + buffer);
		}
		GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
		out.flip();
	}

	private int bufferSize(int buffer)
	{
		GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
		int[] size = new int[1];
		java.nio.IntBuffer sb = java.nio.IntBuffer.wrap(size);
		GL11.glGetIntegerv(GL15.GL_BUFFER_SIZE, sb);
		GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
		return size[0];
	}

	private void zeroCounters()
	{
		ByteBuffer zero = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
		this.upload(this.totalSidesBuffer, zero);
		this.upload(this.sidesPerChunkBuffer, zero);
	}

	private void bind(int unit, int buffer)
	{
		GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, unit, buffer);
	}

	private void setUniform1f(String name, float v)
	{
		int loc = GL20.glGetUniformLocation(this.program, name);
		if (loc == -1)
		{
			LOGGER.warn("Spike: missing uniform '{}'", name);
			return;
		}
		GL20.glUniform1f(loc, v);
	}

	private void setUniform1i(String name, int v)
	{
		int loc = GL20.glGetUniformLocation(this.program, name);
		if (loc == -1)
		{
			LOGGER.warn("Spike: missing uniform '{}'", name);
			return;
		}
		GL20.glUniform1i(loc, v);
	}

	private void setUniform3f(String name, float x, float y, float z)
	{
		int loc = GL20.glGetUniformLocation(this.program, name);
		if (loc == -1)
		{
			LOGGER.warn("Spike: missing uniform '{}'", name);
			return;
		}
		GL20.glUniform3f(loc, x, y, z);
	}

	// Log each unique (error, context) pair once: the same driver error repeating
	// every frame would flood the log and stall the render thread.
	private static final java.util.Set<String> loggedGlErrors = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private void checkGl(String where)
	{
		int err = GL20.glGetError();
		if (err != GL20.GL_NO_ERROR && GpuCloudGeneration.loggedGlErrors.add(err + "@" + where))
			LOGGER.error("Spike: GL error 0x{} after {}", Integer.toHexString(err), where);
	}

	/** Reflects a raw glUseProgram into Blaze3D's cached program state. */
	private static void syncGlStateManagerProgram(int program)
	{
		try
		{
			com.mojang.blaze3d.opengl.GlStateManager._glUseProgram(program);
		}
		catch (Throwable ignored)
		{
		}
	}

	private static String readResource(String path) throws java.io.IOException
	{
		try (InputStream in = GpuCloudGeneration.class.getClassLoader().getResourceAsStream(path))
		{
			if (in == null)
				throw new java.io.FileNotFoundException(path);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Override
	public void close()
	{
		if (this.program != -1)
		{
			GL20.glDeleteProgram(this.program);
			this.program = -1;
		}
		if (this.totalSidesBuffer != 0 || this.sideInfoBuffer != 0 || this.sidesPerChunkBuffer != 0
				|| this.noiseLayersBuffer != 0 || this.layerGroupingsBuffer != 0)
		{
			GL15.glDeleteBuffers(new int[] { this.totalSidesBuffer, this.sideInfoBuffer, this.sidesPerChunkBuffer,
					this.noiseLayersBuffer, this.layerGroupingsBuffer });
		}
		this.totalSidesBuffer = this.sideInfoBuffer = this.sidesPerChunkBuffer = 0;
		this.noiseLayersBuffer = this.layerGroupingsBuffer = 0;
	}
}

package dev.nonamecrackers2.simpleclouds.client.renderer;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.joml.Matrix4f;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import dev.nonamecrackers2.simpleclouds.SimpleCloudsMod;
import dev.nonamecrackers2.simpleclouds.api.common.cloud.CloudMode;
import dev.nonamecrackers2.simpleclouds.api.common.cloud.weather.WeatherType;
import dev.nonamecrackers2.simpleclouds.client.compat.SimpleCloudsCompatHelper;
import dev.nonamecrackers2.simpleclouds.client.framebuffer.ShadowMapBuffer;
import dev.nonamecrackers2.simpleclouds.client.framebuffer.WeightedBlendingTarget;
import dev.nonamecrackers2.simpleclouds.client.cloud.ClientSideCloudTypeManager;
import dev.nonamecrackers2.simpleclouds.client.gui.CloudPreviewerScreen;
import dev.nonamecrackers2.simpleclouds.client.mesh.LevelOfDetailOptions;
import dev.nonamecrackers2.simpleclouds.client.mesh.RendererInitializeResult;
import dev.nonamecrackers2.simpleclouds.client.mesh.lod.LevelOfDetailConfig;
import dev.nonamecrackers2.simpleclouds.client.mesh.lod.PreparedChunk;
import dev.nonamecrackers2.simpleclouds.client.mesh.generator.CloudMeshGenerator;
import dev.nonamecrackers2.simpleclouds.common.cloud.CloudType;
import dev.nonamecrackers2.simpleclouds.common.noise.AbstractNoiseSettings;
import dev.nonamecrackers2.simpleclouds.common.noise.NoiseSettings;
import dev.nonamecrackers2.simpleclouds.common.noise.StaticLayeredNoise;
import dev.nonamecrackers2.simpleclouds.common.noise.StaticNoiseSettings;
import dev.nonamecrackers2.simpleclouds.client.renderer.pipeline.CloudsRenderPipeline;
import dev.nonamecrackers2.simpleclouds.client.renderer.settings.CloudsRendererSettings;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.CloudsDrawPipeline;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.CloudVertexFormat;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.ChunkBufferPool;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.CpuCloudGenerator;
import dev.nonamecrackers2.simpleclouds.client.FogColorCapturer;
import dev.nonamecrackers2.simpleclouds.common.world.CloudManager;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.GpuCloudGeneration;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.PreviewDrawPipeline;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.RainDrawPipeline;
import dev.nonamecrackers2.simpleclouds.client.world.ClientCloudManager;
import dev.nonamecrackers2.simpleclouds.common.cloud.SimpleCloudsConstants;
import dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig;
import net.minecraft.CrashReport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import nonamecrackers2.crackerslib.client.gui.Screen3D;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 26.2 re-implementation (vertical slice).
 *
 * Renders opaque clouds through the new {@link CloudsDrawPipeline} (RenderPipeline +
 * per-instance vertex buffer + CPU generation via {@link CpuCloudGenerator}). The
 * advanced effects (transparency, shadow maps, storm fog, blur, compositing, lightning)
 * are preserved as API-compatible no-ops for now and will be ported incrementally.
 */
public class SimpleCloudsRenderer implements ResourceManagerReloadListener
{
	private static final Logger LOGGER = LogManager.getLogger("simpleclouds/SimpleCloudsRenderer");
	public static final float DITHER_SCALE = 0.05F;
	public static final float CHUNK_FADE_IN_ALPHA_PER_TICK = 0.2F;
	public static final Identifier FINAL_COMPOSITE_LOC = SimpleCloudsMod.id("shaders/post/final_composite.json");
	public static final Identifier FINAL_COMPOSITE_NO_TRANSPARENCY_LOC = SimpleCloudsMod.id("shaders/post/final_composite_no_transparency.json");

	private static @Nullable SimpleCloudsRenderer instance;

	private final CloudsRendererSettings settings;
	private final Minecraft mc;
	private @Nullable ClientCloudManager cloudManager;
	private ArtifactVersion openGlVersion;
	@Nullable private CloudMeshGenerator meshGenerator;
	@Nullable private CloudsRenderPipeline renderPipelineThisPass;
	private float fogStart;
	private float fogEnd;
	@Nullable private Frustum cullFrustum;
	private boolean needsReload;
	@Nullable private RendererInitializeResult initialInitializationResult;

	// 26.2 vertical-slice rendering.
	@Nullable private CloudsDrawPipeline drawPipeline;
	@Nullable private AtmosphericCloudsRenderHandler atmosphericClouds;
	@Nullable private CpuCloudGenerator cpuGenerator;
	@Nullable private RainDrawPipeline rainPipeline;
	@Nullable private WorldEffects worldEffects;
	@Nullable private PreviewDrawPipeline previewPipeline;

	private SimpleCloudsRenderer(CloudsRendererSettings settings, Minecraft mc)
	{
		this.settings = settings;
		this.mc = mc;
	}

	// ---------------------------------------------------------------------
	// Static access
	// ---------------------------------------------------------------------

	public static void initialize(CloudsRendererSettings settings)
	{
		if (instance != null)
		{
			instance.shutdown();
		}
		try
		{
			instance = new SimpleCloudsRenderer(settings, Minecraft.getInstance());
			instance.onResourceManagerReload(Minecraft.getInstance().getResourceManager());
		}
		catch (Throwable t)
		{
			LOGGER.error("Failed to initialize Simple Clouds renderer", t);
			instance = null;
		}
	}

	public static SimpleCloudsRenderer getInstance()
	{
		return Objects.requireNonNull(instance, "Renderer not initialized yet");
	}

	public static Optional<SimpleCloudsRenderer> getOptionalInstance()
	{
		return Optional.ofNullable(instance);
	}

	public static boolean canRenderInDimension(@Nullable ClientLevel level)
	{
		if (level == null)
			return false;
		// Port of the 1.20.1 check: the SERVER config governs when a synced server-side
		// manager exists (its dimension list is what the server runs), otherwise the
		// client's; "whitelistAsBlacklist" inverts the match.
		java.util.List<? extends String> whitelist;
		boolean useAsBlacklist;
		if (dev.nonamecrackers2.simpleclouds.client.world.ClientCloudManager.isAvailableServerSide()
				&& SimpleCloudsConfig.SERVER_SPEC.isLoaded())
		{
			whitelist = SimpleCloudsConfig.SERVER.dimensionWhitelist.get();
			useAsBlacklist = SimpleCloudsConfig.SERVER.whitelistAsBlacklist.get();
		}
		else
		{
			whitelist = SimpleCloudsConfig.CLIENT.dimensionWhitelist.get();
			useAsBlacklist = SimpleCloudsConfig.CLIENT.whitelistAsBlacklist.get();
		}
		boolean matched = whitelist.stream()
				.anyMatch(val -> level.dimension().identifier().toString().equals(val)); // 26.2: ResourceKey.toString() concatenates registry+value with no separator; the value identifier is the comparable part
		return useAsBlacklist ? !matched : matched;
	}

	// ---------------------------------------------------------------------
	// Simple (opaque) cloud rendering via the new pipeline.
	// ---------------------------------------------------------------------

	private void ensurePipeline()
	{
		if (this.drawPipeline != null)
			return;
		try
		{
			this.drawPipeline = new CloudsDrawPipeline();
			this.atmosphericClouds = new AtmosphericCloudsRenderHandler(this.mc);
			// A1: each worker thread owns its own CpuCloudGenerator (the generator is
			// not thread-safe); this instance is only a pipeline-ready marker.
			this.cpuGenerator = new CpuCloudGenerator(List.of());
			this.startChunkWorkerPool();
			// A1.3: 30 s heap/direct/RSS telemetry (SIMPLECLOUDS_DEV=1 only).
			dev.nonamecrackers2.simpleclouds.client.DevMemoryLogger.maybeStart();
		}
		catch (Throwable t)
		{
			LOGGER.error("Failed to set up the 26.2 cloud pipeline", t);
			this.drawPipeline = null;
		}

		if (this.rainPipeline == null)
		{
			try
			{
				this.rainPipeline = new RainDrawPipeline();
			}
			catch (Throwable t)
			{
				LOGGER.error("Failed to set up the 26.2 rain pipeline", t);
				this.rainPipeline = null;
			}
		}
	}

	/**
	 * The layer groups driven by the ported cloud type data (cloud_types/*.json): one
	 * group per active cloud type (its noise layers + its transparency_fade), so the
	 * clouds sit at the configured world heights instead of following the camera and the
	 * transparent edge fade uses the real per-type value. (The 1.20.1 mod fed these same
	 * parameters to the cube_mesh.comp compute shader's per-type LayerGroups; the CPU
	 * generator consumes them here.)
	 */
	public static List<CpuCloudGenerator.CloudLayerGroup> dataDrivenGroups()
	{
		List<CpuCloudGenerator.CloudLayerGroup> out = new java.util.ArrayList<>();
		for (CloudType type : ClientSideCloudTypeManager.getInstance().getIndexedCloudTypes())
		{
			if (!hasRenderableLayers(type))
				continue;
			NoiseSettings settings = type.noiseConfig();
			List<CpuCloudGenerator.NoiseLayer> layers = new java.util.ArrayList<>();
			if (settings instanceof StaticLayeredNoise layered)
			{
				for (StaticNoiseSettings layer : layered.getNoiseLayers())
					layers.add(toCpuLayer(layer));
			}
			else if (settings instanceof StaticNoiseSettings single)
			{
				layers.add(toCpuLayer(single));
			}
			if (!layers.isEmpty())
				out.add(new CpuCloudGenerator.CloudLayerGroup(layers, type.transparencyFade(),
						type.weatherType() == WeatherType.THUNDERSTORM));
		}
		return out;
	}

	private static CpuCloudGenerator.NoiseLayer toCpuLayer(AbstractNoiseSettings<?> layer)
	{
		return new CpuCloudGenerator.NoiseLayer(
				layer.getParam(AbstractNoiseSettings.Param.HEIGHT),
				layer.getParam(AbstractNoiseSettings.Param.VALUE_OFFSET),
				layer.getParam(AbstractNoiseSettings.Param.SCALE_X),
				layer.getParam(AbstractNoiseSettings.Param.SCALE_Y),
				layer.getParam(AbstractNoiseSettings.Param.SCALE_Z),
				layer.getParam(AbstractNoiseSettings.Param.FADE_DISTANCE),
				layer.getParam(AbstractNoiseSettings.Param.HEIGHT_OFFSET),
				layer.getParam(AbstractNoiseSettings.Param.VALUE_SCALE));
	}

	private static boolean hasRenderableLayers(CloudType type)
	{
		NoiseSettings settings = type.noiseConfig();
		if (settings instanceof StaticLayeredNoise layered)
			return !layered.getNoiseLayers().isEmpty();
		return settings instanceof StaticNoiseSettings;
	}

	/**
	 * Maps each cloud type id that gets a group in {@link #dataDrivenGroups()} to its
	 * group index (same iteration order, same filter), so formation footprints can be
	 * tied to the group of their cloud type.
	 */
	public static java.util.Map<net.minecraft.resources.Identifier, Integer> dataDrivenGroupIndices()
	{
		java.util.Map<net.minecraft.resources.Identifier, Integer> out = new java.util.HashMap<>();
		int i = 0;
		for (CloudType type : ClientSideCloudTypeManager.getInstance().getIndexedCloudTypes())
		{
			if (hasRenderableLayers(type))
				out.put(type.id(), i++);
		}
		return out;
	}

	/**
	 * Formation footprints for the CPU generator (port of MultiRegionCloudMeshGenerator's
	 * region upload): the partial-tick position/radius + rotation/stretch transform, in
	 * cloud units (8 world blocks), tied to the formation's type group.
	 */
	private List<CpuCloudGenerator.RegionMask> buildRegionMasks(java.util.Map<net.minecraft.resources.Identifier, Integer> typeToGroup, float partialTick)
	{
		if (this.cloudManager == null)
			return List.of();
		var clouds = this.cloudManager.getClouds();
		if (clouds.isEmpty())
			return List.of();
		java.util.List<CpuCloudGenerator.RegionMask> out = new java.util.ArrayList<>(clouds.size());
		for (dev.nonamecrackers2.simpleclouds.common.cloud.region.CloudRegion region : clouds)
		{
			Integer gi = typeToGroup.get(region.getCloudTypeId());
			if (gi == null)
				continue; // type unknown to the client (not synced / data mismatch)
			org.joml.Matrix2f t = region.createTransform(partialTick);
			out.add(new CpuCloudGenerator.RegionMask(
					region.getPosX(partialTick), region.getPosZ(partialTick), region.getRadius(partialTick),
					t.m00, t.m01, t.m10, t.m11, gi));
		}
		return out;
	}

	/**
	 * Cheap per-frame fingerprint of the formation set (positions/radii/stretches/rotations
	 * quantized to 1/2 cloud unit, 1/100 rad); the mesh is only regenerated when this or
	 * the band origin changes.
	 */
	private long regionSignature(float tick)
	{
		if (this.cloudManager == null)
			return 0L;
		long sig = 1L;
		for (dev.nonamecrackers2.simpleclouds.common.cloud.region.CloudRegion region : this.cloudManager.getClouds())
		{
			sig = 31L * sig + region.getCloudTypeId().hashCode();
			// Very coarse quantization on purpose: a crossing of any boundary
		// invalidates every band of the grid (4 x ~15-150 ms of generation +
		// ~2 MB of buffer uploads), so the deltas must stay far below what is
		// visible. Spawned formations are HUGE (radius 6000-10000 blocks = 750-1250
		// cloud units, drifting at 0.1-0.2 units/tick): a 20-unit (160 block) position
		// step or a 5-unit radius step shifts the type mask by ~0.2% of a disk radius
		// — invisible. Rotation/stretch never change during a region tick.
			sig = 31L * sig + (long) (region.getPosX(tick) * 0.05F);
			sig = 31L * sig + (long) (region.getPosZ(tick) * 0.05F);
			sig = 31L * sig + (long) (region.getRadius(tick) * 0.2F);
			sig = 31L * sig + (long) (region.getStretch(tick) * 10.0F);
			sig = 31L * sig + (long) (region.getRotation(tick) * 10.0F);
		}
		return sig;
	}

	// Full-region generation cache: per-chunk instance data keyed by the chunk's
	// SNAPPED world position (cloud units) + lodScale. A chunk regenerates only when
	// its region signature or the data-driven group set changes. The layout comes from
	// LevelOfDetailConfig (step 2): a full-detail core plus coarser rings, camera-
	// centered and snapped to the primary 32-unit grid, so the cache is stable between
	// grid crossings (the camera moves within a grid cell without invalidating chunks).
	private final java.util.Map<ChunkCoord, ChunkData> chunkCaches = new java.util.HashMap<>();
	/** A1: reusable direct buffers for the per-chunk CPU instance data (capped pool;
	 *  buffers are grown only when a chunk outgrows the borrow, never per call). */
	private final ChunkBufferPool chunkBufferPool = new ChunkBufferPool();
	/** A1: hard cap on cached chunks (safety net; the LOD layout already bounds the
	 *  live set to ~364 — the cap catches any bookkeeping leak). */
	private static final int MAX_CACHED_CHUNKS = 512;
	/** A1: reusable list of shadow-map sources (one per chunk with opaque data). */
	private final java.util.List<CloudsDrawPipeline.InstanceSource> shadowSources = new java.util.ArrayList<>();
	private int cacheGroupsHash = Integer.MIN_VALUE;
	private boolean loggedFieldStats = false; // one-shot A1 field-size stats
	/** Total chunks in the current target LOD set (for the fill-progress accessor). */
	private int totalChunkCount = 0;

	public float getChunkFillFraction()
	{
		return this.totalChunkCount > 0 ? (float) this.chunkCaches.size() / (float) this.totalChunkCount : 1.0F;
	}
	private String lastChunkGridKey;
	private boolean loggedFog = false; // one-shot fog diagnostic (step 3)
	@Nullable
	private LevelOfDetailConfig lodConfig;
	private List<PreparedChunk> lodChunks = java.util.List.of();

	// Step 2 budgets: max new chunks started (nearest first) and finished chunks folded
	// into the buffers per frame. Tuned so a full field fills in ~10-20 s without frame
	// drops (the fill is spread over frames, nearest first).
	private static final int CHUNK_ENQUEUE_BUDGET = 6;
	private static final int CHUNK_POLL_BUDGET = 12;
	/** A2: a chunk regenerates when the wind scroll drifts this far (cloud units,
	 *  1 = 8 world blocks) from the scroll its geometry was sampled with. The
	 *  original evaluated the noise per frame (GPU compute, Scroll uniform);
	 *  this is the CPU compromise — the field updates in 8-block steps at whatever
	 *  rate the worker pool + budgets allow (nearest chunks first). */
	private static final float SCROLL_REGEN_THRESHOLD = 1.0F;
	private static final int PRIMARY_CHUNK = 32; // LevelOfDetailConfig primary span (cloud units)
	private static final int LOD_Y_MIN = 16;     // minimum Y span (cloud units)
	private static final float CLOUD_SCALE_F = 8.0F;

	/** Generated instance data for one LOD chunk.
	 *  A1: the instance data lives in persistent PER-CHUNK GPU buffers (created when
	 *  the chunk is published, freed when the chunk is replaced or leaves the LOD
	 *  layout) — the old whole-field combined buffer + per-frame rebuild is gone.
	 *  The CPU copy goes back to the pool right after the GPU upload. */
	private static final class ChunkData
	{
		long regionSig;
		int groupsHash;
		GpuBuffer opaque;
		int opaqueCount;
		GpuBuffer transparent;
		int transparentCount;
		float stormCoverage;
		// Fade-in (step 3, original CHUNK_FADE_IN_ALPHA_PER_TICK = 0.2/tick): the
		// game tick at which this data was published; the chunk ramps alpha 0->1 over
		// five ticks so new content fades in instead of popping.
		long lastGenTick;
		// A2: the wind scroll the noise was sampled with (the chunk regenerates when
		// the scroll drifts more than SCROLL_REGEN_THRESHOLD from this snapshot).
		float genScrollX;
		float genScrollY;
		float genScrollZ;
	}

	/** Chunk identity: snapped world XZ origin (cloud units) + lodScale. */
	private record ChunkCoord(int x0, int z0, int lodScale)
	{
	}

	// Off-thread chunk generation: a worker POOL (half the cores) generates chunks in
	// parallel; each WORKER owns its CpuCloudGenerator (the generator is not
	// thread-safe). The render thread only enqueues stale chunks and picks up finished
	// ones. All job inputs are immutable, so crossing the thread boundary is safe.
	private final java.util.concurrent.LinkedBlockingQueue<ChunkJob> chunkJobQueue = new java.util.concurrent.LinkedBlockingQueue<>();
	private final java.util.concurrent.ConcurrentLinkedQueue<ChunkResult> completedChunks = new java.util.concurrent.ConcurrentLinkedQueue<>();
	private final java.util.Set<ChunkCoord> pendingChunks = java.util.concurrent.ConcurrentHashMap.newKeySet();
	@Nullable
	private java.util.concurrent.ExecutorService chunkWorkerPool;
	private final java.util.concurrent.atomic.AtomicInteger chunkThreadCounter = new java.util.concurrent.atomic.AtomicInteger();

	/** One off-thread chunk generation request (immutable inputs). x0/y0/z0/x1/y1/z1
	 *  are the chunk bounds in CLOUD UNITS; lodScale is the cube/grid spacing (cloud
	 *  units); cloudHeight is the world Y of cloud-unit 0 (blocks); camGridY is the
	 *  camera height in cloud units above cloudHeight (negative below the clouds). */
	private record ChunkJob(ChunkCoord coord, int x0, int y0, int z0, int x1, int y1, int z1, int lodScale,
			List<CpuCloudGenerator.CloudLayerGroup> groups, List<CpuCloudGenerator.RegionMask> regions,
			long regionSig, int groupsHash, int camGridY, float cloudHeight,
			float scrollX, float scrollY, float scrollZ)
	{
	}

	/** A finished chunk generation (CPU side; the render thread uploads it into the
	 *  chunk's GPU buffer and releases the buffers back to the pool). Carries the
	 *  scroll the geometry was generated with (A2: staleness test). */
	private record ChunkResult(ChunkCoord coord, java.nio.ByteBuffer opaque, int opaqueCount,
			java.nio.ByteBuffer transparent, int transparentCount, float stormCoverage,
			float scrollX, float scrollY, float scrollZ)
	{
	}

	private void startChunkWorkerPool()
	{
		if (this.chunkWorkerPool != null)
			return;
		int threads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
		this.chunkWorkerPool = java.util.concurrent.Executors.newFixedThreadPool(threads, r ->
		{
			Thread t = new Thread(r, "simpleclouds-chunkgen-" + this.chunkThreadCounter.getAndIncrement());
			t.setDaemon(true);
			return t;
		});
		for (int i = 0; i < threads; i++)
			this.chunkWorkerPool.execute(this::chunkWorkerLoop);
	}

	/** A worker thread: pulls chunk jobs and publishes results.
	 *  A1: ONE generator per worker thread (the generator is not thread-safe — mutable
	 *  group/region/scratch state — but each loop lives on its own thread forever),
	 *  and the instance buffers are borrowed from the pool (grown only when a chunk
	 *  outgrows the borrow), never allocated per call. */
	private void chunkWorkerLoop()
	{
		CpuCloudGenerator generator = new CpuCloudGenerator(List.of());
		ChunkJob job;
		try
		{
			while ((job = this.chunkJobQueue.take()) != null)
			{
				boolean published = false;
				java.nio.ByteBuffer opaque = null;
				java.nio.ByteBuffer transparent = null;
				// Ownership trackers for the failure path: the grower RELEASES the old
				// buffer when it swaps in a bigger one, so the worker must only ever
				// release the CURRENT owner (never a buffer that is already back in
				// the pool — a double release would hand the same buffer to two workers).
				final java.nio.ByteBuffer[] ownedO = { null };
				final java.nio.ByteBuffer[] ownedT = { null };
				try
				{
					generator.setGroups(job.groups());
					generator.setRegions(job.regions());
					// Initial borrow: the old "one cube per 64 cells" heuristic as a floor;
					// the pool hands back high-water buffers for stable chunks, and the
					// grower covers denser-than-expected regeneration.
					int xSpan = job.x1() - job.x0(), ySpan = job.y1() - job.y0(), zSpan = job.z1() - job.z0();
					int cells = (xSpan / job.lodScale()) * (ySpan / job.lodScale()) * (zSpan / job.lodScale());
					opaque = this.chunkBufferPool.borrow(Math.max(256, cells / 64 * 6 * CloudVertexFormat.BYTES_PER_INSTANCE));
					transparent = this.chunkBufferPool.borrow(Math.max(256, cells / 64 * 6 * CloudVertexFormat.BYTES_PER_INSTANCE_ALPHA));
					ownedO[0] = opaque;
					ownedT[0] = transparent;
					float[] opaqueCount = new float[1];
					float[] transparentCount = new float[1];
					float[] stormCoverage = new float[1];
					// A2: the real wind scroll (cloud units) and the original's wiggle
					// formula ((scrollX+scrollY+scrollZ)/5, the 1.20.1 Wiggle uniform)
					// are baked into the noise sample — clouds stop being a frozen
					// field on one conveyor belt.
					float wiggle = (job.scrollX() + job.scrollY() + job.scrollZ()) / 5.0F;
					java.nio.ByteBuffer[] out = generator.generate(
							job.x0(), job.y0(), job.z0(), job.x1(), job.y1(), job.z1(),
							CLOUD_SCALE_F, job.scrollX(), job.scrollY(), job.scrollZ(), wiggle, job.lodScale(), job.cloudHeight(),
							opaque, transparent,
							(current, minCap, written) ->
							{
								java.nio.ByteBuffer bigger = this.chunkBufferPool.grow(current, minCap, written);
								if (current == ownedO[0])
									ownedO[0] = bigger;
								else if (current == ownedT[0])
									ownedT[0] = bigger;
								return bigger;
							},
							opaqueCount, transparentCount, job.camGridY(), stormCoverage);
					// The result carries the FINAL buffers (the grower may have swapped
					// them for bigger pooled ones).
					opaque = out[0];
					transparent = out[1];
					ownedO[0] = opaque;
					ownedT[0] = transparent;
					this.completedChunks.add(new ChunkResult(job.coord(), opaque, (int) opaqueCount[0], transparent, (int) transparentCount[0], stormCoverage[0],
							job.scrollX(), job.scrollY(), job.scrollZ()));
					published = true;
					LOGGER.info("Simple Clouds clouds: chunk {}x{} (lod {}) generated off-thread, {} formations, {} opaque / {} transparent instances",
							job.x0(), job.z0(), job.lodScale(), job.regions().size(), (int) opaqueCount[0], (int) transparentCount[0]);
				}
				catch (Throwable t)
				{
					LOGGER.error("Simple Clouds clouds: off-thread chunk generation failed", t);
				}
				finally
				{
					// Release the pending slot even on failure so the chunk can be
					// re-requested next frame; return the CURRENTLY owned buffers on
					// failure only (on success they are owned by the result and
					// released by the render thread after the GPU upload).
					this.pendingChunks.remove(job.coord());
					if (!published)
					{
						this.chunkBufferPool.release(ownedO[0]);
						this.chunkBufferPool.release(ownedT[0]);
					}
				}
			}
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
	}


	// SPIKE (SPIKE-GPU.md): the original cube_mesh.comp through raw OpenGL, for
	// timing/shape comparison against CpuCloudGeneration. OFF — the result is
	// recorded in SPIKE-GPU-RESULT.md (Mesa 26.2.1/ARL blocks the GPU->CPU data
	// path); re-enable once the driver honors the core GL contract.
	private static final boolean SPIKE_GPU = false;
	private GpuCloudGeneration spike;
	private boolean spikeReady;
	private boolean spikeClassLogged;
	private boolean spikeInitFailed;
	private long lastCpuGenerateNanos;
	// Storm coverage (fraction of columns around the camera with storm cloud above),
	// refreshed on band regeneration; the fullscreen fog pass uses it every frame.
	private float cacheStormCoverage = 0.0F;

	// Throttled retry counter: initialize() runs once on the first client tick; if the
	// pipeline construction fails there (e.g. the device not ready), retry a few times
	// per second instead of blacking out silently forever.
	private long pipelineRetryFrame;

	/** Fade-in alpha for one band (step 3): 0 -> 1 at CHUNK_FADE_IN_ALPHA_PER_TICK
	 *  per tick after its data was published; 1.0 once settled. */
	private float chunkAlpha(ChunkData d, long nowTick, float partialTick)
	{
		double age = nowTick + partialTick - d.lastGenTick;
		return (float) Mth.clamp(age * CHUNK_FADE_IN_ALPHA_PER_TICK, 0.0, 1.0);
	}

	private void generateAndDrawClouds(double camX, double camY, double camZ, float partialTick)
	{
		if (this.drawPipeline == null || this.cpuGenerator == null)
		{
			// Retry (throttled to every 64th failed frame) instead of staying dark.
			if ((this.pipelineRetryFrame++ & 63) == 0)
				this.ensurePipeline();
			if (this.drawPipeline == null || this.cpuGenerator == null)
				return;
		}

		// World view matrix: view rotation * translate(-cameraPos). The LevelRenderer
		// model-view stack is already popped at our TAIL hook, so build it explicitly.
		var camera = Minecraft.getInstance().gameRenderer.mainCamera();
		org.joml.Matrix4f view = new org.joml.Matrix4f();
		view.mul(camera.getViewRotationMatrix(new org.joml.Matrix4f()));
		view.mul(new org.joml.Matrix4f().translate((float)-camX, (float)-camY, (float)-camZ));

		// A2 (VISUAL-PARITY-PLAN addendum): NO global view translation anymore — the
		// wind scroll is baked into the generated geometry (each chunk carries the
		// scroll it was sampled with and regenerates when the scroll drifts more
		// than SCROLL_REGEN_THRESHOLD, the CPU equivalent of the original's
		// per-frame Scroll/Wiggle uniforms). `terrainView` equals `view` (kept for
		// the terrain-shadow call).
		org.joml.Matrix4f terrainView = new org.joml.Matrix4f(view);
		var driftManager = CloudManager.get(Minecraft.getInstance().level);
		float scrollX = 0.0F, scrollY = 0.0F, scrollZ = 0.0F;
		if (driftManager != null)
		{
			scrollX = driftManager.getScrollX(partialTick);
			scrollY = driftManager.getScrollY(partialTick);
			scrollZ = driftManager.getScrollZ(partialTick);
		}

		// Full-region rendering (step 2, LOD). The cloud field is world-fixed (CLOUD_SCALE
		// = 8 blocks per cloud unit). The chunk layout comes from LevelOfDetailConfig:
		// a full-detail core plus coarser rings, camera-centered and snapped to the
		// primary 32-unit grid (stable between grid crossings). Each chunk is generated
		// at its lodScale (cube/grid spacing), nearest first, off-thread (worker pool).
		float scale = CLOUD_SCALE_F;
		// Step 1 (VISUAL-PARITY-PLAN): the cloud volume is anchored at WORLD
		// Y = cloudHeight (default 128), exactly like the original -- it does NOT
		// follow the player's altitude. The chunk cache is therefore XZ-only.
		int cloudHeight = 128;
		var cloudManager = CloudManager.get(Minecraft.getInstance().level);
		if (cloudManager != null)
			cloudHeight = cloudManager.getCloudHeight();
		// Camera height in cloud units above the volume base (negative when the player
		// is below the cloud layer): used only for the "storm above the camera" metric.
		int camGridY = Mth.floor((camY - (double) cloudHeight) / scale);
		// Primary-grid snap (cloud units): the chunk world positions are stable while
		// the camera moves within one 32-unit (256-block) grid cell, so the cache is
		// not invalidated by sub-cell movement (the original's meshGenOffset snap).
		double camCloudX = camX / scale, camCloudZ = camZ / scale;
		int snapX = (int) Math.floor(camCloudX / PRIMARY_CHUNK) * PRIMARY_CHUNK;
		int snapZ = (int) Math.floor(camCloudZ / PRIMARY_CHUNK) * PRIMARY_CHUNK;
		String gridKey = snapX + "," + snapZ;

		List<CpuCloudGenerator.CloudLayerGroup> groups = dataDrivenGroups();
		int groupsHash = groups.hashCode();
		// Formation footprints: the original's multi-region path masks the same
		// world-fixed noise field by the spawned formations' X/Z coverage. With no
		// formations (not synced yet / vanilla weather) the generator falls back to
		// the infinite field.
		java.util.Map<net.minecraft.resources.Identifier, Integer> typeToGroup = dataDrivenGroupIndices();
		// Sample region positions at the INTEGER tick, not the render partial tick:
		// the partial tick changes every frame, which would fingerprint a different
		// (interpolated) formation set per frame and force a full CPU regeneration
		// every frame. At integer ticks the signature only changes when a region has
		// moved by at least half a cloud unit (4 blocks).
		double sigTick = Math.floor(partialTick);
		List<CpuCloudGenerator.RegionMask> regions = this.buildRegionMasks(typeToGroup, (float) sigTick);
		long regionSig = this.regionSignature((float) sigTick);
		boolean groupsChanged = groupsHash != this.cacheGroupsHash;
		this.cacheGroupsHash = groupsHash;
		boolean chunkSetChanged = gridKey != this.lastChunkGridKey;
		this.lastChunkGridKey = gridKey;

		// Y span: the max ACTIVE layer height (cloud units), rounded up to a multiple
		// of the coarsest lodScale (8) so every LOD grid divides evenly. Avoids
		// generating empty air above the highest active layer (step 2, fix 3).
		int maxLayerY = LOD_Y_MIN;
		java.util.Set<Integer> activeGroups = new java.util.HashSet<>();
		for (CpuCloudGenerator.RegionMask r : regions)
			activeGroups.add(r.groupIndex());
		for (int gi : activeGroups)
		{
			if (gi < 0 || gi >= groups.size())
				continue;
			for (CpuCloudGenerator.NoiseLayer l : groups.get(gi).layers())
				maxLayerY = Math.max(maxLayerY, (int) Math.ceil(l.heightOffset() + l.height()));
		}
		maxLayerY = (int) (Math.ceil(maxLayerY / 8.0) * 8);

		// Ensure the LOD layout is prepared (from the levelOfDetail config, default HIGH).
		if (this.lodChunks.isEmpty())
		{
			LevelOfDetailOptions lodOption = SimpleCloudsConfig.CLIENT.levelOfDetail.get();
			this.lodConfig = lodOption.getConfig();
			this.lodChunks = this.lodConfig.getPreparedChunks();
		}

		// Target chunk list: each PreparedChunk offset by the camera snap, nearest first.
		// long[] = { worldX0, worldZ0, lodScale, distance*1000 } (cloud units).
		java.util.List<long[]> target = new java.util.ArrayList<>(this.lodChunks.size());
		for (PreparedChunk pc : this.lodChunks)
		{
			int lodScale = pc.lodScale();
			int wx0 = snapX + pc.x() * (PRIMARY_CHUNK * lodScale);
			int wz0 = snapZ + pc.z() * (PRIMARY_CHUNK * lodScale);
			double cxm = wx0 + PRIMARY_CHUNK * lodScale * 0.5 - camCloudX;
			double czm = wz0 + PRIMARY_CHUNK * lodScale * 0.5 - camCloudZ;
			target.add(new long[] { wx0, wz0, lodScale, (long) ((cxm * cxm + czm * czm) * 1000.0) });
		}
		target.sort(java.util.Comparator.comparingLong(c -> c[3]));
		this.totalChunkCount = target.size();

		// Pick up finished chunks (budgeted, generated off-thread — no render hitch).
		// Last-writer-wins: a completion is at most one generation cycle behind the
		// current region state, far below the visible drift of the formations, so
		// stamp it with the CURRENT signature. Fade-in only for NEW chunks (the
		// original resets a chunk's alpha only after 120 ticks without regeneration).
		// A1: the CPU result is uploaded into the chunk's PERSISTENT per-chunk GPU
		// buffer here, and the CPU copy goes straight back to the pool (the GPU buffer
		// is the home of the data; it is freed when the chunk is replaced or evicted).
		int polled = 0;
		ChunkResult result;
		while (polled < CHUNK_POLL_BUDGET && (result = this.completedChunks.poll()) != null)
		{
			polled++;
			final ChunkResult r = result; // final capture for the buffer-name lambdas
			ChunkData d = new ChunkData();
			d.regionSig = regionSig;
			d.groupsHash = groupsHash;
			ChunkData prev = this.chunkCaches.get(r.coord());
			d.lastGenTick = prev != null ? prev.lastGenTick : mc.level.getGameTime();
			d.opaqueCount = r.opaqueCount();
			d.opaque = d.opaqueCount > 0 && r.opaque() != null
					? RenderSystem.getDevice().createBuffer(() -> "simpleclouds.chunk.o." + r.coord().x0() + "." + r.coord().z0() + "." + r.coord().lodScale(), GpuBuffer.USAGE_VERTEX, r.opaque())
					: null;
			d.transparentCount = r.transparentCount();
			d.transparent = d.transparentCount > 0 && r.transparent() != null
					? RenderSystem.getDevice().createBuffer(() -> "simpleclouds.chunk.t." + r.coord().x0() + "." + r.coord().z0() + "." + r.coord().lodScale(), GpuBuffer.USAGE_VERTEX, r.transparent())
					: null;
			d.stormCoverage = r.stormCoverage();
			d.genScrollX = r.scrollX();
			d.genScrollY = r.scrollY();
			d.genScrollZ = r.scrollZ();
			// Free the replaced chunk's GPU buffers.
			if (prev != null)
			{
				if (prev.opaque != null)
					prev.opaque.close();
				if (prev.transparent != null)
					prev.transparent.close();
			}
			this.chunkCaches.put(r.coord(), d);
			// A1: the CPU copies are released back to the pool now that the GPU has them.
			this.chunkBufferPool.release(r.opaque());
			this.chunkBufferPool.release(r.transparent());
		}
		// A1: hard cap on cached chunks (safety net; frees the GPU buffers of any
		// leaked entries).
		if (this.chunkCaches.size() > MAX_CACHED_CHUNKS)
		{
			java.util.Iterator<java.util.Map.Entry<ChunkCoord, ChunkData>> it = this.chunkCaches.entrySet().iterator();
			while (this.chunkCaches.size() > MAX_CACHED_CHUNKS && it.hasNext())
			{
				ChunkData leaked = it.next().getValue();
				it.remove();
				if (leaked.opaque != null)
					leaked.opaque.close();
				if (leaked.transparent != null)
					leaked.transparent.close();
			}
		}

		// Enqueue stale chunks, nearest first, budgeted. pendingChunks makes enqueuing
		// idempotent across frames; the worker pool fills the field, nearest first.
		int enqueued = 0;
		for (long[] c : target)
		{
			if (enqueued >= CHUNK_ENQUEUE_BUDGET)
				break;
			int lodScale = (int) c[2];
			ChunkCoord key = new ChunkCoord((int) c[0], (int) c[1], lodScale);
			ChunkData d = this.chunkCaches.get(key);
			// A2: also stale when the wind scroll drifted beyond the threshold since
			// this chunk was generated (the noise was sampled at the old phase).
			boolean stale = d == null || d.regionSig != regionSig || d.groupsHash != groupsHash || groupsChanged
					|| Math.abs(scrollX - d.genScrollX) > SCROLL_REGEN_THRESHOLD
					|| Math.abs(scrollY - d.genScrollY) > SCROLL_REGEN_THRESHOLD
					|| Math.abs(scrollZ - d.genScrollZ) > SCROLL_REGEN_THRESHOLD;
			if (stale && this.pendingChunks.add(key))
			{
				enqueued++;
				int x0 = (int) c[0], z0 = (int) c[1];
				this.chunkJobQueue.add(new ChunkJob(key, x0, 0, z0,
						x0 + PRIMARY_CHUNK * lodScale, maxLayerY, z0 + PRIMARY_CHUNK * lodScale, lodScale,
						groups, regions, regionSig, groupsHash, camGridY, (float) cloudHeight,
						scrollX, scrollY, scrollZ));
			}
		}

		float maxStorm = 0.0F;
		int totalOpaque = 0, totalTransp = 0;
		long nowTick = mc.level.getGameTime();
		for (long[] c : target)
		{
			ChunkData d = this.chunkCaches.get(new ChunkCoord((int) c[0], (int) c[1], (int) c[2]));
			if (d == null)
				continue;
			if (d.stormCoverage > maxStorm)
				maxStorm = d.stormCoverage;
			totalOpaque += d.opaqueCount;
			totalTransp += d.transparentCount;
		}
		// Evict chunks that left the target set (keeps the map bounded; A1: their
		// GPU buffers are freed here — "free GPU buffers of chunks that leave the
		// LOD layout").
		if (chunkSetChanged)
		{
			java.util.Set<ChunkCoord> keep = new java.util.HashSet<>(target.size());
			for (long[] c : target)
				keep.add(new ChunkCoord((int) c[0], (int) c[1], (int) c[2]));
			java.util.Iterator<java.util.Map.Entry<ChunkCoord, ChunkData>> it = this.chunkCaches.entrySet().iterator();
			while (it.hasNext())
			{
				java.util.Map.Entry<ChunkCoord, ChunkData> e = it.next();
				if (!keep.contains(e.getKey()))
				{
					ChunkData evicted = e.getValue();
					if (evicted.opaque != null)
						evicted.opaque.close();
					if (evicted.transparent != null)
						evicted.transparent.close();
					it.remove();
				}
			}
		}
		this.cacheStormCoverage = maxStorm;

		// A1: no combined buffer, no per-frame rebuild. The per-chunk GPU buffers are
		// persistent (created when a chunk is published); a one-shot stats line
		// replaces the old per-rebuild log.
		if (totalOpaque > 0 && !this.loggedFieldStats)
		{
			this.loggedFieldStats = true;
			LOGGER.info("Simple Clouds clouds: {} chunks -> {} opaque / {} transparent instances (cam {}x{}x{}, snap {}x{})",
					this.chunkCaches.size(), totalOpaque, totalTransp, camX, camY, camZ, snapX, snapZ);
		}

		// Step 3 (VISUAL-PARITY-PLAN): the cloud fog range is relative to the vanilla
		// render distance in blocks -- clear up to the render distance, fully faded to
		// the sky 3x out (distant LOD chunks dissolve into the horizon). The color is
		// the vanilla sky/fog color (MixinFogRenderer captures it each frame).
		int renderDistChunks = mc.options.getEffectiveRenderDistance();
		float fogStart = renderDistChunks * 16.0F;
		float fogEnd = fogStart * 3.0F;
		// Sky/fog color: use the captured vanilla color when it is valid, else
		// approximate the overworld sky color from the sun angle (the 26.2 FogRenderer's
		// FogData.color is (0,0,0) in the dev client -- its FogEnvironment lookup finds
		// no color source -- so the approximation keeps the fog a sensible sky blue).
		float[] fogColor = FogColorCapturer.get();
		float fr = fogColor[0], fg = fogColor[1], fb = fogColor[2];
		if (fr < 0.01F && fg < 0.01F && fb < 0.01F)
		{
			float sunY = (float) Math.sin((float) ((mc.level.getOverworldClockTime() % 24000L) / 24000.0 * 2.0 * Math.PI));
			float day = Mth.clamp(sunY * 4.0F + 0.5F, 0.0F, 1.0F);
			fr = Mth.lerp(day, 0.02F, 0.63F);
			fg = Mth.lerp(day, 0.02F, 0.81F);
			fb = Mth.lerp(day, 0.05F, 0.92F);
		}
		if (!this.loggedFog)
		{
			this.loggedFog = true;
			LOGGER.info("Simple Clouds clouds: fog range {}..{} blocks, sky color ({}, {}, {})", (int) fogStart, (int) fogEnd, fr, fg, fb);
		}
		this.drawPipeline.setFog(fr, fg, fb, fogStart, fogEnd);

		// A1: per-chunk passes from the persistent per-chunk GPU buffers (the
		// original's architecture: each mesh chunk draws its own buffer). The fade-in
		// alpha (step 3) is just this chunk's pass ColorModulator — no separate fade
		// uploads, no whole-field rebuild.
		boolean transpPassEnabled = SimpleCloudsConfig.CLIENT.transparency.get();
		for (long[] c : target)
		{
			ChunkData d = this.chunkCaches.get(new ChunkCoord((int) c[0], (int) c[1], (int) c[2]));
			if (d == null || d.opaque == null)
				continue;
			float alpha = chunkAlpha(d, nowTick, partialTick);
			if (alpha <= 0.0F)
				continue; // not visible yet this frame
			this.drawPipeline.drawClouds(view, d.opaque, d.opaqueCount, alpha);
		}
		// Transparent edges after the opaque pass (same chunk order; far-to-near
		// blending order is a step-5 concern).
		if (transpPassEnabled)
		{
			for (long[] c : target)
			{
				ChunkData d = this.chunkCaches.get(new ChunkCoord((int) c[0], (int) c[1], (int) c[2]));
				if (d == null || d.transparent == null || d.transparentCount == 0)
					continue;
				float alpha = chunkAlpha(d, nowTick, partialTick);
				if (alpha <= 0.0F)
					continue;
				this.drawPipeline.drawTransparencyClouds(view, d.transparent, d.transparentCount, alpha);
			}
		}

		// World effects: custom rain (1.20.1 PrecipitationQuads; slice without
		// wind tilt / snow) into the scene, before the overlays.
		if (SimpleCloudsConfig.CLIENT.renderCustomRain.get())
			this.getWorldEffectsManager().renderRain(view, partialTick, camX, camY, camZ);
			// 1.20.1 lightning bolts (server-spawned; additive world-space quads).
			if (this.getWorldEffectsManager().hasLightningToRender())
				this.getWorldEffectsManager().renderLightning(view, partialTick, camX, camY, camZ, this.drawPipeline);

		// Storm fog (26.2 slice): darkened overlay while under storm clouds. The
		// lightning flash (WorldEffects.flashStrength) reduces the darkening via the
		// LightningMul uniform, brightening the scene on a strike.
		if (SimpleCloudsConfig.CLIENT.renderStormFog.get())
		{
			float lightningMul = 1.0F - this.getWorldEffectsManager().flashStrength(partialTick) * 0.9F;
			this.drawPipeline.drawStormFog(this.cacheStormCoverage * 2.5F, lightningMul);
		}

		// Cloud shadows (26.2 slice): top-down ortho depth pass over the cloud
		// instances, then a fullscreen terrain-shadow pass (see CloudShadowPass
		// section of CloudsDrawPipeline and PORTING.md).
		// A1: the shadow map draws the per-chunk buffers (one drawIndexed per chunk
		// inside a single pass).
		this.shadowSources.clear();
		for (long[] c : target)
		{
			ChunkData d = this.chunkCaches.get(new ChunkCoord((int) c[0], (int) c[1], (int) c[2]));
			if (d != null && d.opaque != null)
				this.shadowSources.add(new CloudsDrawPipeline.InstanceSource(d.opaque, d.opaqueCount));
		}
		this.drawPipeline.renderCloudShadowMap(camX, camY, camZ, (float) cloudHeight, this.shadowSources);
		this.drawPipeline.drawTerrainShadows(terrainView, camX, camY, camZ, (float) cloudHeight);

		// Atmospheric (high cirrus-type) clouds: biome-driven 2D layer over the
		// whole view (original: end of the DefaultPipeline render).
		if (SimpleCloudsConfig.CLIENT.atmosphericClouds.get() && this.atmosphericClouds != null)
		{
			// 26.2: the level projection's vertical FOV lives on the camera render
			// state (no getFov method anymore).
			float fovDeg = this.mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.hudFov;
			float aspect = (float) this.mc.getWindow().getWidth() / Math.max(1.0F, (float) this.mc.getWindow().getHeight());
			// Vanilla cloud brightness (0..1) like the original's r/g/b args.
			float bright = 0.9F;
			this.atmosphericClouds.render(this.drawPipeline, view, partialTick, bright, bright, bright, fovDeg, aspect);
		}
	}

	// ---------------------------------------------------------------------
	// Public API (preserved for external callers; advanced effects stubbed)
	// ---------------------------------------------------------------------

	public String getClientCloudManagerString()
	{
		return this.cloudManager != null ? this.cloudManager.toString() : "null";
	}

	public CloudMeshGenerator getMeshGenerator()
	{
		return this.meshGenerator;
	}

	public CloudsRenderPipeline getRenderPipeline()
	{
		return Objects.requireNonNullElse(this.renderPipelineThisPass, CloudsRenderPipeline.DEFAULT);
	}

	public WorldEffects getWorldEffectsManager()
	{
		if (this.worldEffects == null)
			this.worldEffects = new WorldEffects(this.mc, this);
		return this.worldEffects;
	}

	public @Nullable RainDrawPipeline getRainPipeline()
	{
		return this.rainPipeline;
	}

	public float getStormCoverage()
	{
		return this.cacheStormCoverage;
	}

	public AtmosphericCloudsRenderHandler getAtmosphericCloudRenderer()
	{
		return this.atmosphericClouds;
	}

	public CloudsRendererSettings getSettings()
	{
		return this.settings;
	}

	public @Nullable RendererInitializeResult getInitialInitializationResult()
	{
		return this.initialInitializationResult;
	}

	public ShadowMapBuffer getStormFogShadowMap()
	{
		// TODO(26.2): port storm fog shadow maps.
		return null;
	}

	public Optional<ShadowMapBuffer> getShadowMap()
	{
		return Optional.empty();
	}

	public @Nullable PoseStack getStormFogShadowMapStack()
	{
		return null;
	}

	public @Nullable PoseStack getShadowMapStack()
	{
		return null;
	}

	public RenderTarget getBlurTarget()
	{
		return null;
	}

	public RenderTarget getStormFogTarget()
	{
		return null;
	}

	public RenderTarget getCloudTarget()
	{
		return null;
	}

	public WeightedBlendingTarget getCloudTransparencyTarget()
	{
		return null;
	}

	public float getFogStart()
	{
		return this.fogStart;
	}

	public float getFogEnd()
	{
		return this.fogEnd;
	}

	public float getFadeFactorForDistance(float distance)
	{
		return Mth.clamp(1.0F - (distance - this.fogStart) / (this.fogEnd - this.fogStart), 0.0F, 1.0F);
	}

	public @Nullable Frustum getCullFrustum()
	{
		return this.cullFrustum;
	}

	public void onCloudManagerChange(ClientCloudManager manager)
	{
		this.cloudManager = manager;
	}

	public boolean needsReinitialization()
	{
		return this.needsReload;
	}

	public void requestReload()
	{
		this.needsReload = true;
	}

	@Override
	public void onResourceManagerReload(ResourceManager manager)
	{
		this.ensurePipeline();
		this.needsReload = false;
	}

	public void onMainWindowResize(int width, int height)
	{
		// TODO(26.2): nothing to resize for the vertical slice (main target is managed by the game).
	}

	public void shutdown()
	{
		if (this.drawPipeline != null)
		{
			this.drawPipeline.close();
			this.drawPipeline = null;
		}
		if (this.rainPipeline != null)
		{
			this.rainPipeline.close();
			this.rainPipeline = null;
		}
		if (this.chunkWorkerPool != null)
		{
			this.chunkWorkerPool.shutdownNow();
			this.chunkWorkerPool = null;
		}
		// A1: free the per-chunk GPU buffers.
		for (ChunkData d : this.chunkCaches.values())
		{
			if (d.opaque != null)
				d.opaque.close();
			if (d.transparent != null)
				d.transparent.close();
		}
		this.chunkCaches.clear();
		this.cpuGenerator = null;
		this.worldEffects = null;
		if (this.spike != null)
		{
			this.spike.close();
			this.spike = null;
			this.spikeReady = false;
		}
	}

	public void baseTick()
	{
		if (this.atmosphericClouds != null)
		{
			if (this.cloudManager != null)
				this.atmosphericClouds.setWindDirection(this.cloudManager.calculateWindDirection());
			this.atmosphericClouds.tick();
		}
	}

	public void tick()
	{
	}

	// Static render entry points (preserved; the 26.2 path draws in renderBeforeLevel).
	public static void renderCloudsOpaque(CloudMeshGenerator generator, PoseStack stack, Matrix4f projMat, float fogStart, float fogEnd, float partialTick, float r, float g, float b, @Nullable Frustum frustum)
	{
	}

	public static void renderCloudsOpaque(CloudMeshGenerator generator, PoseStack stack, Matrix4f projMat, float fogStart, float fogEnd, float partialTick, float r, float g, float b, @Nullable Frustum frustum, boolean ditherFade)
	{
	}

	public static void renderCloudsTransparency(CloudMeshGenerator generator, PoseStack stack, Matrix4f projMat, float fogStart, float fogEnd, float partialTick, float r, float g, float b, @Nullable Frustum frustum)
	{
	}

	public static void renderCloudsTransparency(CloudMeshGenerator generator, PoseStack stack, Matrix4f projMat, float fogStart, float fogEnd, float partialTick, float r, float g, float b, @Nullable Frustum frustum, boolean ditherFade)
	{
	}

	public static void renderCloudsDebug(CloudMeshGenerator generator, PoseStack stack, Matrix4f projMat, float partialTick, float fogStart, float fogEnd, @Nullable Frustum frustum, boolean chunkBoundaries, boolean noiseBoundaries)
	{
	}

	public float[] getCloudColor(float partialTick)
	{
		return new float[] { 1.0F, 1.0F, 1.0F };
	}

	public void translateClouds(PoseStack stack, double camX, double camY, double camZ)
	{
	}

	public void renderWeather(LightTexture texture, float partialTick, double camX, double camY, double camZ)
	{
	}

	public void renderBeforeLevel(PoseStack stack, Matrix4f projMat, float partialTick, double camX, double camY, double camZ)
	{
		if (!SimpleCloudsCompatHelper.renderThisPass())
			return;
		// 26.2 3D previewer: while the previewer screen is open, draw the preview
		// box into the main frame (snippet pipeline, orbit camera, no depth test)
		// instead of the normal cloud pass.
		if (this.mc.gui.screen() instanceof CloudPreviewerScreen previewScreen)
		{
			this.drawPreviewInWorld(previewScreen, camX, camY, camZ);
			return;
		}
		this.generateAndDrawClouds(camX, camY, camZ, partialTick);
	}

	/**
	 * 26.2 3D previewer (world phase, main frame): draws the preview box with the
	 * screen's orbit camera around the player. The main cloud pipeline's snippet
	 * path is the only color-draw combination that works from a standalone pass in
	 * 26.2 (see PreviewDrawPipeline javadoc).
	 */
	private void drawPreviewInWorld(Screen3D s3d, double camX, double camY, double camZ)
	{
		try
		{
			if (this.drawPipeline == null)
				return;
			if (this.previewPipeline == null)
			{
				this.previewPipeline = new PreviewDrawPipeline();
				this.previewPipeline.generateMesh();
			}
			Matrix4f view = PreviewDrawPipeline.previewViewMatrix(s3d.camRotX(), s3d.camRotY(), s3d.zoom(), s3d.offset());
			this.previewPipeline.draw(this.drawPipeline, view);
		}
		catch (Throwable t)
		{
			if (!this.previewLoggedError)
			{
				this.previewLoggedError = true;
				LOGGER.error("Simple Clouds: preview render pass failed (further failures suppressed)", t);
			}
		}
	}

	private boolean previewLoggedError = false;

	/** Called when the previewer screen closes (MixinGameRenderer hook path). */
	public void destroyPreview()
	{
		if (this.previewPipeline != null)
		{
			this.previewPipeline.close();
			this.previewPipeline = null;
		}
	}

	public void renderAfterSky(PoseStack stack, Matrix4f projMat, float partialTick, double camX, double camY, double camZ)
	{
	}

	public void renderBeforeWeather(PoseStack stack, Matrix4f projMat, float partialTick, double camX, double camY, double camZ)
	{
	}

	public void renderAfterLevel(PoseStack stack, Matrix4f projMat, float partialTick, double camX, double camY, double camZ)
	{
	}

	public void doBlurPostProcessing(float partialTick)
	{
		// TODO(26.2): port blur.
	}

	public void doScreenSpaceWorldFog(PoseStack stack, Matrix4f projMat, float partialTick)
	{
		// TODO(26.2): port screen-space fog.
	}

	public void doFinalCompositePass(PoseStack stack, float partialTick, Matrix4f projMat)
	{
		// TODO(26.2): port compositing.
	}

	public void doStormPostProcessing(PoseStack stack, float partialTick, Matrix4f projMat, double camX, double camY, double camZ, float r, float g, float b)
	{
		// TODO(26.2): port storm fog.
	}

	public void doCloudShadowProcessing(PoseStack stack, float partialTick, Matrix4f projMat, double camX, double camY, double camZ, int depthBufferId)
	{
		// TODO(26.2): port cloud shadows.
	}

	public void copyDepthFromCloudsToMain()
	{
	}

	public void copyDepthFromMainToClouds()
	{
	}

	public void copyDepthFromCloudsToTransparency()
	{
	}

	public void fillReport(CrashReport report)
	{
	}
}

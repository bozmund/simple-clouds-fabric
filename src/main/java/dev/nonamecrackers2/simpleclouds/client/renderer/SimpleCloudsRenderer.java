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

import com.mojang.renderpearl.api.buffers.GpuBuffer;
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
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.CloudWorldCoverage;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.CloudFaceCoverage;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.CloudVertexFormat;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.ChunkBufferPool;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.CpuCloudGenerator;
import dev.nonamecrackers2.simpleclouds.client.FogColorCapturer;
import dev.nonamecrackers2.simpleclouds.common.world.CloudManager;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.GpuCloudGeneration;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.GpuStormColumns;
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
	private static CloudType[] selectedCloudTypes()
	{
		CloudType[] available = ClientSideCloudTypeManager.getInstance().getIndexedCloudTypes();
		var level = Minecraft.getInstance().level;
		if (level == null) return available;
		var manager = CloudManager.get(level);
		if (manager == null) return available;
		return dev.nonamecrackers2.simpleclouds.client.cloud.CloudTypeSelection.select(
				manager.getCloudMode(), manager.getSingleModeCloudTypeRawId(), available,
				ClientCloudManager.isAvailableServerSide());
	}

	public static List<CpuCloudGenerator.CloudLayerGroup> dataDrivenGroups()
	{
		return dataDrivenGroups(selectedCloudTypes());
	}

	/** Explicit source for previews, independent of the world's current cloud mode. */
	public static List<CpuCloudGenerator.CloudLayerGroup> dataDrivenGroups(CloudType[] types)
	{
		List<CpuCloudGenerator.CloudLayerGroup> out = new java.util.ArrayList<>();
		for (CloudType type : types)
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
						type.weatherType() == WeatherType.THUNDERSTORM,
						// Step 8: per-type storm shading (dark undersides).
						type.storminess(), type.stormStart(), type.stormFadeDistance()));
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
		for (CloudType type : selectedCloudTypes())
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
	private boolean usesRegionMasks()
	{
		return this.cloudManager == null || this.cloudManager.getCloudMode()
				!= dev.nonamecrackers2.simpleclouds.api.common.cloud.CloudMode.SINGLE;
	}

	private List<CpuCloudGenerator.RegionMask> buildRegionMasks(java.util.Map<net.minecraft.resources.Identifier, Integer> typeToGroup, float partialTick)
	{
		if (this.cloudManager == null)
			return List.of();
		if (this.cloudManager.getCloudMode() == dev.nonamecrackers2.simpleclouds.api.common.cloud.CloudMode.SINGLE)
			return List.of(); // Infinite selected type; ignore leftover multi-region cells.
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

	// Full-region generation cache: per-chunk instance data keyed by the chunk's
	// SNAPPED world position (cloud units) + lodScale. A chunk regenerates only when
	// its region signature or the data-driven group set changes. The layout comes from
	// LevelOfDetailConfig (step 2): a full-detail core plus coarser rings, camera-
	// centered and snapped to the primary 32-unit grid, so the cache is stable between
	// grid crossings (the camera moves within a grid cell without invalidating chunks).
	private final java.util.Map<ChunkCoord, ChunkData> chunkCaches = new java.util.HashMap<>();
	private final java.util.Map<ChunkCoord, dev.nonamecrackers2.simpleclouds.client.renderer.v2.CloudGridTransition.Retained<ChunkCoord, ChunkData>> retainedGridChunks = new java.util.HashMap<>();
	private int cachedSnapX, cachedSnapZ;
	// CPU cache entries are indexes, not owners. World fragments own each
	// shared buffer until its last covered area has actually been replaced.
	private final CloudWorldCoverage<ChunkCoord, ChunkData> worldCoverage = new CloudWorldCoverage<>(SimpleCloudsRenderer::closeChunk);
	private final java.util.concurrent.ConcurrentHashMap<ChunkCoord, List<CloudWorldCoverage.Fragment<ChunkData>>> batchWorldSources = new java.util.concurrent.ConcurrentHashMap<>();

	private static CloudWorldCoverage.Rect worldBounds(ChunkCoord coord)
	{
		int span = PRIMARY_CHUNK * coord.lodScale();
		return new CloudWorldCoverage.Rect(coord.x0(), coord.z0(), coord.x0() + span, coord.z0() + span, coord.lodScale());
	}

	private ChunkData drawableChunk(ChunkCoord coord)
	{
		ChunkData exact = this.chunkCaches.get(coord);
		if (WORLD_COVERAGE)
		{
			var fragments = this.worldCoverage.fragments(coord);
			return exact != null ? exact : fragments.isEmpty() ? null : fragments.getFirst().source();
		}
		var retained = this.retainedGridChunks.get(coord);
		return exact != null ? exact : retained != null ? retained.value() : null;
	}
	/** A1: reusable direct buffers for the per-chunk CPU instance data (capped pool;
	 *  buffers are grown only when a chunk outgrows the borrow, never per call). */
	private final ChunkBufferPool chunkBufferPool = new ChunkBufferPool();
	/** A1: hard cap on cached chunks (safety net; the LOD layout already bounds the
	 *  live set to ~364 — the cap catches any bookkeeping leak). */
	private static final int MAX_CACHED_CHUNKS = 512;
	/** A1: reusable list of shadow-map sources (one per chunk with opaque data). */
	private final java.util.List<CloudsDrawPipeline.InstanceSource> shadowSources = new java.util.ArrayList<>();
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
	// Development discriminator: after reducing CPU generation cost, the old
	// six-starts/frame ceiling itself may limit refresh cadence. Keep production
	// and experimental GPU scheduling unchanged until the higher bound is tested.
	private static final boolean CPU_BUDGET_PROBE = "1".equals(System.getenv("SIMPLECLOUDS_DEV"))
			&& "1".equals(System.getenv("SIMPLECLOUDS_CPU_BUDGET_PROBE"));
	private static final int CPU_ENQUEUE_BUDGET = CPU_BUDGET_PROBE ? 32 : CHUNK_ENQUEUE_BUDGET;
	private static final int CPU_POLL_BUDGET = CPU_BUDGET_PROBE ? 32 : CHUNK_POLL_BUDGET;
	private static final int CHUNK_REFRESH_BUDGET = 96;
	private static final int PRIMARY_CHUNK = 32; // LevelOfDetailConfig primary span (cloud units)
	private static final int LOD_Y_MIN = 16;     // minimum Y span (cloud units)
	private static final float CLOUD_SCALE_F = 8.0F;

	/** Generated instance data for one LOD chunk.
	 *  A1: the instance data lives in persistent PER-CHUNK GPU buffers (created when
	 *  the chunk is published, freed when the chunk is replaced or leaves the LOD
	 *  layout) — the old whole-field combined buffer + per-frame rebuild is gone.
	 *  The pooled generation buffers are released after upload; a compact heap
	 *  copy remains so the next generation can identify unchanged faces. */
	private static final class ChunkData
	{
		ChunkCoord sourceCoord;
		ChunkData departing;
		long regionSig;
		int groupsHash;
		GpuBuffer opaque;
		int opaqueCount;
		GpuBuffer opaqueAdded;
		int opaqueAddedCount;
		// Experimental GPU-local opaque transition state for the next generation.
		GpuBuffer gpuOpaqueRaw;
		GpuBuffer gpuOpaqueIds;
		int gpuOpaqueRawCount;
		GpuBuffer gpuTransparentRaw;
		GpuBuffer gpuTransparentIds;
		int gpuTransparentRawCount;
		GpuBuffer transparent;
		int transparentCount;
		GpuBuffer transparentAdded;
		int transparentAddedCount;
		byte[] opaqueRaw;
		byte[] transparentRaw;
		boolean gpuResidentOnly;
		float stormCoverage;
		// Plan item 3: the chunk's storm columns (storm-type cloud above the camera, index
		// ix * stormZCells + iz) for the spatial storm-fog map.
		byte[] stormColumns;
		int stormXCells;
		int stormZCells;
		// Fade-in (step 3, original CHUNK_FADE_IN_ALPHA_PER_TICK = 0.2/tick): the
		// game tick at which this data was published; the chunk ramps alpha 0->1 over
		// five ticks so new content fades in instead of popping.
		long lastGenTick;
		// Noise sample time, not a world-space translation of the completed mesh.
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
	private final java.util.ArrayDeque<ChunkJob> batchWaiting = new java.util.ArrayDeque<>();
	// Opt-in development backend. Never enabled by an ordinary profile launch.
	private static final boolean GPU_WORLD_EXPERIMENT = "1".equals(System.getenv("SIMPLECLOUDS_GPU_WORLD"));
	// Opt-in diagnostic for sudden visible replacements during a cloud refresh.
	private static final boolean TRACE_VISUAL_CHURN = "1".equals(System.getenv("SIMPLECLOUDS_TRACE_VISUAL_CHURN"));
	private static final boolean GPU_DIRECT_OPAQUE_EXPERIMENT = GPU_WORLD_EXPERIMENT
			&& "1".equals(System.getenv("SIMPLECLOUDS_GPU_DIRECT_OPAQUE"));
	private static final boolean GPU_DIRECT_TRANSPARENT_EXPERIMENT = GPU_WORLD_EXPERIMENT
			&& "1".equals(System.getenv("SIMPLECLOUDS_GPU_DIRECT_TRANSPARENT"));
	private static final boolean GPU_STORM_BITS_EXPERIMENT = GPU_WORLD_EXPERIMENT
			&& "1".equals(System.getenv("SIMPLECLOUDS_GPU_STORM_BITS"));
	private static final boolean GPU_NO_READBACK_EXPERIMENT = GPU_DIRECT_OPAQUE_EXPERIMENT
			&& GPU_DIRECT_TRANSPARENT_EXPERIMENT && GPU_STORM_BITS_EXPERIMENT
			&& "1".equals(System.getenv("SIMPLECLOUDS_GPU_NO_READBACK"));
	// CPU and plain GPU-readback sources both have complete heap face records.
	// Share physical ownership/clipping there. Direct-delta GPU experiments still
	// require a fragment-aware GPU predecessor path before they can use this.
	private static final boolean WORLD_COVERAGE = !GPU_WORLD_EXPERIMENT
			|| (!GPU_DIRECT_OPAQUE_EXPERIMENT && !GPU_DIRECT_TRANSPARENT_EXPERIMENT);
	private static final boolean GPU_TEST_CPU_FALLBACK = GPU_WORLD_EXPERIMENT
			&& "1".equals(System.getenv("SIMPLECLOUDS_DEV"))
			&& "1".equals(System.getenv("SIMPLECLOUDS_GPU_TEST_CPU_FALLBACK"));
	private static final int MAX_GPU_FACE_IDS = 32 * 256 * 32 * 6;
	// Development-only concurrency probe; production keeps the bounded four
	// slots until the larger configurations pass full-pack visual and memory tests.
	private static final int GPU_WORLD_SLOTS = GPU_WORLD_EXPERIMENT
			&& "1".equals(System.getenv("SIMPLECLOUDS_DEV"))
			&& "16".equals(System.getenv("SIMPLECLOUDS_GPU_WORLD_SLOTS")) ? 16
			: GPU_WORLD_EXPERIMENT && "1".equals(System.getenv("SIMPLECLOUDS_DEV"))
				&& "8".equals(System.getenv("SIMPLECLOUDS_GPU_WORLD_SLOTS")) ? 8 : 4;
	private static final int GPU_POST_LIMIT = 8;
	private static final class GpuSlot
	{
		final GpuCloudGeneration generator;
		@Nullable ChunkJob job;
		long startedNanos;
		GpuSlot(GpuCloudGeneration generator) { this.generator = generator; }
	}
	private final GpuSlot[] gpuSlots = new GpuSlot[GPU_WORLD_SLOTS];
	@Nullable private dev.nonamecrackers2.simpleclouds.client.renderer.v2.GpuFaceDelta gpuFaceDelta;
	private final java.util.Map<ChunkCoord,GpuOpaqueCandidate> pendingGpuOpaque = new java.util.HashMap<>();
	@Nullable private dev.nonamecrackers2.simpleclouds.client.renderer.v2.GpuTransparentExpansion gpuTransparentExpansion;
	@Nullable private dev.nonamecrackers2.simpleclouds.client.renderer.v2.GpuFaceDelta gpuTransparentDelta;
	@Nullable private dev.nonamecrackers2.simpleclouds.client.renderer.v2.GpuStormColumnBits gpuStormBits;
	private final java.util.Map<ChunkCoord,GpuTransparentCandidate> pendingGpuTransparent = new java.util.HashMap<>();
	private static final class GpuOpaqueCandidate implements AutoCloseable
	{
		final long batch;
		GpuBuffer raw, ids, stable, added, removed;
		int rawCount, stableCount, addedCount, removedCount;
		boolean useGpuDelta;
		GpuOpaqueCandidate(long batch) { this.batch = batch; }
		void adoptRawOnly(ChunkData current)
		{
			current.gpuOpaqueRaw = this.raw; this.raw = null;
			current.gpuOpaqueIds = this.ids; this.ids = null;
			current.gpuOpaqueRawCount = this.rawCount;
		}
		void adopt(ChunkData current, ChunkData faded)
		{
			this.adoptRawOnly(current);
			current.opaque = this.stable; this.stable = null;
			current.opaqueCount = this.stableCount;
			current.opaqueAdded = this.added; this.added = null;
			current.opaqueAddedCount = this.addedCount;
			faded.opaque = this.removed; this.removed = null;
			faded.opaqueCount = this.removedCount;
		}
		@Override public void close()
		{
			for (GpuBuffer buffer : new GpuBuffer[] { this.raw, this.ids, this.stable, this.added, this.removed })
				if (buffer != null) buffer.close();
			this.raw = this.ids = this.stable = this.added = this.removed = null;
		}
	}
	private static final class GpuTransparentCandidate implements AutoCloseable
	{
		final long batch;
		GpuBuffer raw, ids, stable, added, removed;
		int rawCount, stableCount, addedCount, removedCount;
		boolean useGpuDelta;
		GpuTransparentCandidate(long batch) { this.batch = batch; }
		void adoptRawOnly(ChunkData current)
		{
			current.gpuTransparentRaw = this.raw; this.raw = null;
			current.gpuTransparentIds = this.ids; this.ids = null;
			current.gpuTransparentRawCount = this.rawCount;
		}
		void adopt(ChunkData current, ChunkData faded)
		{
			this.adoptRawOnly(current);
			current.transparent = this.stable; this.stable = null;
			current.transparentCount = this.stableCount;
			current.transparentAdded = this.added; this.added = null;
			current.transparentAddedCount = this.addedCount;
			faded.transparent = this.removed; this.removed = null;
			faded.transparentCount = this.removedCount;
		}
		@Override public void close()
		{
			for (GpuBuffer buffer : new GpuBuffer[] { this.raw, this.ids, this.stable, this.added, this.removed })
				if (buffer != null) buffer.close();
			this.raw = this.ids = this.stable = this.added = this.removed = null;
		}
	}
	@Nullable private java.util.concurrent.ExecutorService gpuPostPool;
	private final java.util.concurrent.atomic.AtomicInteger gpuPostPending = new java.util.concurrent.atomic.AtomicInteger();
	private boolean gpuWorldFailed;
	private long gpuOnlyCompleted;
	private final java.util.Set<ChunkCoord> batchExpected = new java.util.HashSet<>();
	private final java.util.Map<ChunkCoord, ChunkData> batchDeltaSources = new java.util.HashMap<>();
	private boolean batchInProgress;
	private long batchId;
	private long publishedBatchCount;
	private long batchStartedNanos;
	private long lastPublishedNanos;
	private int lastLoggedGenerationConfig = Integer.MIN_VALUE;
	private long lastUnpausedGameTick;
	private float lastUnpausedPartialTick;
	private long initialSyncWaitStartedTick = Long.MIN_VALUE;
	private boolean initialSyncTimeoutLogged;
	private boolean initialFieldRevealed;
	private long initialFieldWaitStartedTick = Long.MIN_VALUE;
	private long initialFieldRevealTick;
	private long summaryPublishedBatches;
	private long summaryBatchDurationNanos;
	private long summaryLongestBatchNanos;
	private long summaryLongestPublishGapNanos;
	private final java.util.Map<ChunkCoord, ChunkData> previousBatch = new java.util.HashMap<>();
	private boolean batchFailed;
	private long retryBatchAfterNanos;
	private boolean hasScrollPhase;
	private float phaseX, phaseY, phaseZ;
	private float batchX, batchY, batchZ;
	private volatile boolean workersStopped;
	private final Object completionLock = new Object();
	private long nextSummaryNanos = System.nanoTime() + 30_000_000_000L;
	private long lastFrameNanos;
	private long worstFrameNanos;
	private long summaryQueued, summaryCompleted, summaryUploadBytes, summaryGenerationNanos;
	private long summaryMissing, summaryMask, summaryScroll, summaryConfig, summaryFailed;
	private final long[] summaryQueuedHeight = new long[4];
	private long summaryChangedFaces;
	private long summaryMaxChangedFacesInFrame;
	private int summaryMaxReplacedInFrame;
	private long summarySupersededFades, summaryInterruptedFades;
	private long summaryGpuSubmitNanos, summaryGpuReadbackNanos, summaryUploadNanos;
	private long summaryGpuSubmits, summaryGpuReadbacks, summaryUploads;
	private long summaryGpuOpaqueBytes, summaryGpuTransparentBytes, summaryGpuEmptyChunks;
	private long summaryGpuDirectOpaque, summaryGpuDirectFallback;
	private long summaryGpuDirectTransparent, summaryGpuTransparentFallback;
	private final java.util.concurrent.atomic.AtomicLong summaryGpuPostNanos = new java.util.concurrent.atomic.AtomicLong();
	private final java.util.concurrent.atomic.AtomicLong summaryGpuPostJobs = new java.util.concurrent.atomic.AtomicLong();

	/** One off-thread chunk generation request (immutable inputs). x0/y0/z0/x1/y1/z1
	 *  are the chunk bounds in CLOUD UNITS; lodScale is the cube/grid spacing (cloud
	 *  units); cloudHeight is the world Y of cloud-unit 0 (blocks); camGridY is the
	 *  camera height in cloud units above cloudHeight (negative below the clouds). */
	private record ChunkJob(ChunkCoord coord, int x0, int y0, int z0, int x1, int y1, int z1, int lodScale,
			List<CpuCloudGenerator.CloudLayerGroup> groups, List<CpuCloudGenerator.RegionMask> regions,
			boolean regionMode, long regionSig, int groupsHash, int camGridY, float cloudHeight,
			float scrollX, float scrollY, float scrollZ,
			float camCloudX, float camCloudZ, int transparencyDistance, long batch,
			byte[] previousOpaque, byte[] previousTransparent)
	{
	}

	/** A finished chunk generation (CPU side; the render thread uploads it into the
	 *  chunk's GPU buffer and releases the buffers back to the pool). Carries the
	 *  scroll the geometry was generated with (A2: staleness test). */
	private record ChunkResult(ChunkCoord coord, java.nio.ByteBuffer opaque, int opaqueCount,
			java.nio.ByteBuffer transparent, int transparentCount, float stormCoverage,
			byte[] stormColumns, int stormXCells, int stormZCells,
			float scrollX, float scrollY, float scrollZ, long regionSig, int groupsHash,
			long batch, long generationNanos, boolean success, boolean fromGpu, boolean gpuOnly,
			dev.nonamecrackers2.simpleclouds.client.renderer.v2.CloudFaceDelta.Split opaqueDelta,
			dev.nonamecrackers2.simpleclouds.client.renderer.v2.CloudFaceDelta.Split transparentDelta,
			@Nullable ChunkJob retryJob)
	{
	}

	private void prepareBatch(List<long[]> target, List<CpuCloudGenerator.CloudLayerGroup> groups,
			List<CpuCloudGenerator.RegionMask> regions, int config, int maxY, int[] groupMaxY,
			int cameraY, int cloudHeight,
			float sx, float sy, float sz, float cameraX, float cameraZ, int transparencyDistance,
			@Nullable Frustum visibleFrustum)
	{
		if (this.batchInProgress
				|| System.nanoTime() < this.retryBatchAfterNanos) return;
		// Original compute path samples fractional Scroll on a world-fixed voxel
		// lattice. Translating a completed mesh moves its region/height boundaries
		// too, which noise scroll must not do. Do not round the sample time.
		this.batchX = sx;
		this.batchY = sy;
		this.batchZ = sz;
		this.batchFailed = false;
		this.batchId++;
		this.batchStartedNanos = System.nanoTime();
		List<CpuCloudGenerator.CloudLayerGroup> immutableGroups = List.copyOf(groups);
		record Candidate(ChunkJob job, int reason, long lastGenTick, boolean visible) {}
		List<Candidate> missingChunks = new java.util.ArrayList<>();
		List<Candidate> near = new java.util.ArrayList<>();
		List<Candidate> distant = new java.util.ArrayList<>();
		var masks = regions.stream().map(r -> new dev.nonamecrackers2.simpleclouds.client.renderer.v2.ChunkGenerationKey.Mask(
				r.x(), r.z(), r.radius(), r.m00(), r.m01(), r.m10(), r.m11(), r.groupIndex())).toList();
		for (long[] c : target)
		{
			int x = (int)c[0], z = (int)c[1], lod = (int)c[2], span = PRIMARY_CHUNK * lod;
			int chunkMaxY = this.usesRegionMasks()
					? dev.nonamecrackers2.simpleclouds.client.renderer.v2.ChunkGenerationKey.localMaxY(
							x, z, span, lod, masks, groupMaxY, LOD_Y_MIN)
					: maxY;
			int chunkConfig = java.util.Objects.hash(config, chunkMaxY);
			ChunkCoord coord = new ChunkCoord(x, z, lod);
			// Let retained face-local dissolves finish before replacing their
			// source again. Retargeting can clip them, but cannot discard them.
			if (WORLD_COVERAGE && this.worldCoverage.fragments(coord).stream().anyMatch(
					p -> p.source().departing != null && chunkAlpha(p.source(),
							Minecraft.getInstance().level.getGameTime(), 0.0F) < 1.0F)) continue;
			ChunkData old = this.chunkCaches.get(coord);
			long key = dev.nonamecrackers2.simpleclouds.client.renderer.v2.ChunkGenerationKey.local(x, z, span, lod, masks);
			boolean missing = old == null;
			ChunkData deltaSource = missing ? this.drawableChunk(coord) : old;
			boolean configChanged = !missing && old.groupsHash != chunkConfig;
			boolean maskChanged = !missing && old.regionSig != key;
			boolean scrollChanged = missing || sx != old.genScrollX || sy != old.genScrollY || sz != old.genScrollZ;
			if (!missing && !configChanged && !maskChanged && !scrollChanged) continue;
			ChunkJob job = new ChunkJob(coord, x, 0, z, x + span, chunkMaxY, z + span, lod,
					immutableGroups, regions, this.usesRegionMasks(), key, chunkConfig, cameraY, cloudHeight,
					this.batchX, this.batchY, this.batchZ, cameraX, cameraZ,
					transparencyDistance, this.batchId,
					deltaSource == null ? null : deltaSource.opaqueRaw, deltaSource == null ? null : deltaSource.transparentRaw);
			int reason = missing ? 0 : configChanged ? 1 : maskChanged ? 2 : 3;
			boolean visible = visibleFrustum == null || visibleFrustum.isVisible(
					new net.minecraft.world.phys.AABB(x * CLOUD_SCALE_F, cloudHeight,
							z * CLOUD_SCALE_F, (x + span) * CLOUD_SCALE_F,
							cloudHeight + chunkMaxY * CLOUD_SCALE_F,
							(z + span) * CLOUD_SCALE_F));
			Candidate candidate = new Candidate(job, reason, missing ? 0 : old.lastGenTick, visible);
			if (missing) missingChunks.add(candidate);
			else if (lod == 1) near.add(candidate);
			else distant.add(candidate);
		}
		// Original generation culls against the camera frustum. Our bounded
		// backend cannot do all 364 chunks per sweep, so favor chunks in view
		// without starving the rest: a visible chunk gets a three-second age
		// credit, while an offscreen chunk eventually wins by actual age.
		// Share the budget across LODs: a fixed near-ring quota previously
		// spent slots on offscreen chunks while visible distant chunks waited.
		for (Candidate candidate : missingChunks) this.queueBatchJob(candidate.job(), candidate.reason());
		List<Candidate> refreshCandidates = new java.util.ArrayList<>(near);
		refreshCandidates.addAll(distant);
		List<Candidate> selected = dev.nonamecrackers2.simpleclouds.client.renderer.v2.CloudRefreshSchedule.select(
				refreshCandidates, CHUNK_REFRESH_BUDGET - missingChunks.size(),
				Candidate::lastGenTick, Candidate::visible);
		for (Candidate candidate : selected) this.queueBatchJob(candidate.job(), candidate.reason());
		long nearSlots = selected.stream().filter(c -> c.job().lodScale() == 1).count();
		long distantSlots = selected.size() - nearSlots;
		if (TRACE_VISUAL_CHURN && this.batchId % 10 == 0)
		{
			long nowTick = Minecraft.getInstance().level.getGameTime();
			java.util.List<Long> visibleAges = java.util.stream.Stream.concat(near.stream(), distant.stream())
					.filter(Candidate::visible)
					.map(c -> Math.max(0L, nowTick - c.lastGenTick()))
					.sorted().toList();
			long ageP50 = visibleAges.isEmpty() ? 0L : visibleAges.get((visibleAges.size() - 1) / 2);
			long ageP95 = visibleAges.isEmpty() ? 0L : visibleAges.get((visibleAges.size() - 1) * 95 / 100);
			long ageMax = visibleAges.isEmpty() ? 0L : visibleAges.get(visibleAges.size() - 1);
			LOGGER.info("[MOTION-SCHEDULE] batch={} frustum={} nearVisible={}/{} distantVisible={}/{} selectedNear={} selectedDistant={} selectedVisibleNear={} selectedVisibleDistant={} visibleAgeTicks[p50={},p95={},max={}]",
					this.batchId, visibleFrustum != null,
					near.stream().filter(Candidate::visible).count(), near.size(),
					distant.stream().filter(Candidate::visible).count(), distant.size(),
					nearSlots, distantSlots,
					selected.stream().filter(c -> c.job().lodScale() == 1 && c.visible()).count(),
					selected.stream().filter(c -> c.job().lodScale() != 1 && c.visible()).count(),
					ageP50, ageP95, ageMax);
		}
		this.batchInProgress = !this.batchExpected.isEmpty();
	}

	private void queueBatchJob(ChunkJob job, int reason)
	{
		this.batchExpected.add(job.coord());
		this.batchDeltaSources.put(job.coord(), this.drawableChunk(job.coord()));
		if (WORLD_COVERAGE) this.batchWorldSources.put(job.coord(), this.worldCoverage.fragments(job.coord()));
		this.batchWaiting.add(job);
		if (TRACE_VISUAL_CHURN)
			this.summaryQueuedHeight[job.y1() <= 16 ? 0 : job.y1() <= 64 ? 1 : job.y1() <= 128 ? 2 : 3]++;
		if (reason == 0) this.summaryMissing++;
		else if (reason == 1) this.summaryConfig++;
		else if (reason == 2) this.summaryMask++;
		else this.summaryScroll++;
	}

	private static void closeChunk(@Nullable ChunkData data)
	{
		if (data == null) return;
		closeChunk(data.departing);
		data.departing = null;
		if (data.opaque != null) data.opaque.close();
		if (data.opaqueAdded != null) data.opaqueAdded.close();
		if (data.gpuOpaqueRaw != null) data.gpuOpaqueRaw.close();
		if (data.gpuOpaqueIds != null) data.gpuOpaqueIds.close();
		if (data.gpuTransparentRaw != null) data.gpuTransparentRaw.close();
		if (data.gpuTransparentIds != null) data.gpuTransparentIds.close();
		if (data.transparent != null) data.transparent.close();
		if (data.transparentAdded != null) data.transparentAdded.close();
	}

	private byte[] predecessorFaces(ChunkJob job, boolean transparent)
	{
		if (!WORLD_COVERAGE) return transparent ? job.previousTransparent() : job.previousOpaque();
		var pieces = this.batchWorldSources.getOrDefault(job.coord(), List.of());
		java.util.List<CloudWorldCoverage.Fragment<byte[]>> sources = new java.util.ArrayList<>(pieces.size());
		for (var piece : pieces)
			sources.add(new CloudWorldCoverage.Fragment<>(piece.bounds(),
					transparent ? piece.source().transparentRaw : piece.source().opaqueRaw));
		return CloudFaceCoverage.gather(sources, transparent ? 28 : 24);
	}

	private List<CloudWorldCoverage.Fragment<ChunkData>> drawablePieces(ChunkCoord coord)
	{
		if (WORLD_COVERAGE) return this.worldCoverage.fragments(coord);
		ChunkData value = this.drawableChunk(coord);
		return value == null ? List.of() : List.of(new CloudWorldCoverage.Fragment<>(worldBounds(value.sourceCoord), value));
	}

	private void drawPiece(org.joml.Matrix4f view, ChunkData d, ChunkData old,
			CloudWorldCoverage.Rect clip, boolean transparent, float startupAlpha, long nowTick, float partialTick)
	{
		float alpha = chunkAlpha(d, nowTick, partialTick);
		if (WORLD_COVERAGE && d.departing != null && alpha >= 1.0F)
		{
			closeChunk(d.departing); d.departing = null; old = null;
		}
		if (transparent)
		{
			if (startupAlpha >= 1.0F && old != null && old.transparent != null && old.transparentCount > 0)
				this.drawPipeline.drawTransparencyClouds(view, old.transparent, old.transparentCount, -(2.0F + alpha), 0,0,0,clip);
			if (d.transparent != null && d.transparentCount > 0)
				this.drawPipeline.drawTransparencyClouds(view, d.transparent, d.transparentCount, startupAlpha, 0,0,0,clip);
			if (d.transparentAdded != null && d.transparentAddedCount > 0 && alpha > 0)
				this.drawPipeline.drawTransparencyClouds(view, d.transparentAdded, d.transparentAddedCount, Math.min(alpha,startupAlpha), 0,0,0,clip);
		}
		else
		{
			if (startupAlpha >= 1.0F && old != null && old.opaque != null)
				this.drawPipeline.drawClouds(view, old.opaque, old.opaqueCount, -(2.0F + alpha), 0,0,0,clip);
			if (d.opaque != null)
				this.drawPipeline.drawClouds(view, d.opaque, d.opaqueCount, startupAlpha, 0,0,0,clip);
			if (d.opaqueAdded != null && alpha > 0)
				this.drawPipeline.drawClouds(view, d.opaqueAdded, d.opaqueAddedCount, Math.min(alpha,startupAlpha), 0,0,0,clip);
		}
	}

	@Nullable
	private static GpuBuffer uploadFaces(String name, byte[] faces)
	{
		if (faces.length == 0) return null;
		java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocateDirect(faces.length);
		bytes.put(faces).flip();
		return RenderSystem.getDevice().createBuffer(() -> name, GpuBuffer.USAGE_VERTEX, bytes);
	}

	private void logGenerationSummary()
	{
		long now = System.nanoTime();
		if (this.lastFrameNanos != 0) this.worstFrameNanos = Math.max(this.worstFrameNanos, now - this.lastFrameNanos);
		this.lastFrameNanos = now;
		if (now < this.nextSummaryNanos) return;
		LOGGER.info("Simple Clouds generation backend={} queued={} completed={} failed={} stale[missing={},mask={},scroll={},config={}] generationMs={} uploadBytes={} worstFrameMs={} pending={} staged={} transformWritesPeakPerFrame={} publishedBatches={} meanBatchMs={} longestBatchMs={} longestPublishGapMs={}",
				!GPU_WORLD_EXPERIMENT ? "CPU" : this.gpuWorldFailed ? "CPU_FALLBACK" : "GPU_EXPERIMENT",
				this.summaryQueued, this.summaryCompleted, this.summaryFailed, this.summaryMissing, this.summaryMask,
				this.summaryScroll, this.summaryConfig, this.summaryGenerationNanos / 1_000_000L,
				this.summaryUploadBytes, this.worstFrameNanos / 1_000_000L, this.batchExpected.size(), 0,
				this.drawPipeline != null ? this.drawPipeline.takePeakFrameTransforms() : 0,
				this.summaryPublishedBatches,
				this.summaryPublishedBatches == 0 ? 0 : this.summaryBatchDurationNanos / this.summaryPublishedBatches / 1_000_000L,
				this.summaryLongestBatchNanos / 1_000_000L,
				this.summaryLongestPublishGapNanos / 1_000_000L);
		if (GPU_WORLD_EXPERIMENT)
			LOGGER.info("Simple Clouds stage timings (summed job time, not wall time): submits={} submitMs={} readbacks={} readbackMs={} readbackOpaqueMiB={} readbackTransparentMiB={} emptyChunks={} directOpaque={} directFallback={} directTransparent={} transparentFallback={} postJobs={} postMs={} uploads={} uploadMs={}",
				this.summaryGpuSubmits, this.summaryGpuSubmitNanos / 1_000_000L,
				this.summaryGpuReadbacks, this.summaryGpuReadbackNanos / 1_000_000L,
				this.summaryGpuOpaqueBytes / 1_048_576L, this.summaryGpuTransparentBytes / 1_048_576L,
				this.summaryGpuEmptyChunks,this.summaryGpuDirectOpaque,this.summaryGpuDirectFallback,
				this.summaryGpuDirectTransparent,this.summaryGpuTransparentFallback,
				this.summaryGpuPostJobs.getAndSet(0), this.summaryGpuPostNanos.getAndSet(0) / 1_000_000L,
				this.summaryUploads, this.summaryUploadNanos / 1_000_000L);
		if (TRACE_VISUAL_CHURN)
			LOGGER.info("[VISUAL-CHURN-SUMMARY] queuedY[<=16={},<=64={},<=128={},>128={}] changedFaces={} maxFrameChangedFaces={} maxFrameReplacedChunks={} supersededFades={} interruptedFades={}",
				this.summaryQueuedHeight[0], this.summaryQueuedHeight[1], this.summaryQueuedHeight[2], this.summaryQueuedHeight[3],
				this.summaryChangedFaces, this.summaryMaxChangedFacesInFrame, this.summaryMaxReplacedInFrame,
				this.summarySupersededFades, this.summaryInterruptedFades);
		java.util.Arrays.fill(this.summaryQueuedHeight, 0);
		this.summaryChangedFaces = this.summaryMaxChangedFacesInFrame = 0;
		this.summaryMaxReplacedInFrame = 0;
		this.summarySupersededFades = this.summaryInterruptedFades = 0;
		this.summaryQueued = this.summaryCompleted = this.summaryFailed = this.summaryMissing = this.summaryMask = 0;
		this.summaryScroll = this.summaryConfig = this.summaryGenerationNanos = this.summaryUploadBytes = this.worstFrameNanos = 0;
		this.summaryGpuSubmits = this.summaryGpuReadbacks = this.summaryUploads = 0;
		this.summaryGpuSubmitNanos = this.summaryGpuReadbackNanos = this.summaryUploadNanos = 0;
		this.summaryGpuOpaqueBytes = this.summaryGpuTransparentBytes = this.summaryGpuEmptyChunks = 0;
		this.summaryGpuDirectOpaque = this.summaryGpuDirectFallback = 0;
		this.summaryGpuDirectTransparent = this.summaryGpuTransparentFallback = 0;
		this.summaryPublishedBatches = this.summaryBatchDurationNanos = this.summaryLongestBatchNanos = this.summaryLongestPublishGapNanos = 0;
		this.nextSummaryNanos = now + 30_000_000_000L;
	}

	public String generationDiagnostic()
	{
		return "phase=" + this.phaseX + "," + this.phaseY + "," + this.phaseZ
				+ " batch=" + this.batchId + " waiting=" + this.batchExpected.size()
				+ " fading=" + this.previousBatch.size();
	}

	public String meshDiagnostic()
	{
		return "faces=" + this.chunkCaches.values().stream().mapToLong(d -> d.opaqueCount + d.opaqueAddedCount).sum()
				+ " previousFaces=" + this.previousBatch.values().stream().mapToLong(d -> d.opaqueCount).sum()
				+ " perChunkFadeTicks=" + (int)(1.0F / CHUNK_FADE_IN_ALPHA_PER_TICK);
	}

	public long getPublishedBatchCount() { return this.publishedBatchCount; }

	/** The continuously refreshed field is ready once its visible layout is filled;
	 * waiting for a frame with no active fade/batch would starve captures forever. */
	public boolean isCaptureFieldSettled()
	{
		return this.getChunkFillFraction() >= 0.97F;
	}

	private void startChunkWorkerPool()
	{
		if (this.chunkWorkerPool != null)
			return;
		this.workersStopped = false;
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
				long started = System.nanoTime();
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
					generator.setRegions(job.regions(), job.regionMode());
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
							opaqueCount, transparentCount, job.camGridY(),
						new float[] { job.camCloudX(), job.camCloudZ() }, stormCoverage,
						job.transparencyDistance());
					// The result carries the FINAL buffers (the grower may have swapped
					// them for bigger pooled ones).
					opaque = out[0];
					transparent = out[1];
					ownedO[0] = opaque;
					ownedT[0] = transparent;
					// Face matching is CPU-heavy. Do it on the generating worker, not
					// on the render thread that must keep drawing every frame.
					var opaqueDelta = dev.nonamecrackers2.simpleclouds.client.renderer.v2.CloudFaceDelta.split(
							this.predecessorFaces(job, false), opaque, CloudVertexFormat.BYTES_PER_INSTANCE);
					var transparentDelta = dev.nonamecrackers2.simpleclouds.client.renderer.v2.CloudFaceDelta.split(
							this.predecessorFaces(job, true), transparent, CloudVertexFormat.BYTES_PER_INSTANCE_ALPHA);
					synchronized (this.completionLock)
					{
						if (!this.workersStopped)
						{
							this.completedChunks.add(new ChunkResult(job.coord(), opaque, (int) opaqueCount[0], transparent, (int) transparentCount[0], stormCoverage[0],
									generator.copyStormColumns(), generator.lastStormXCells(), generator.lastStormZCells(),
									job.scrollX(), job.scrollY(), job.scrollZ(), job.regionSig(), job.groupsHash(), job.batch(), System.nanoTime() - started, true, false, false,
								opaqueDelta, transparentDelta,null));
							published = true;
						}
					}
				}
				catch (Throwable t)
				{
					synchronized (this.completionLock)
					{
						if (!this.workersStopped)
							this.completedChunks.add(new ChunkResult(job.coord(), null, 0, null, 0, 0, null, 0, 0,
									job.scrollX(), job.scrollY(), job.scrollZ(), job.regionSig(), job.groupsHash(), job.batch(), System.nanoTime() - started, false, false, false,
									null, null,null));
					}
					LOGGER.debug("Simple Clouds chunk generation failure", t);
				}
				finally
				{
					// Release the pending slot even on failure so the chunk can be
					// re-requested next frame; return the CURRENTLY owned buffers on
					// failure only (on success they are owned by the result and
					// released by the render thread after the GPU upload).
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

	/** Polls a bounded set of GPU jobs without waiting. CPU face comparison and
	 * storm reconstruction remain off the render thread. */
	private void serviceGpuWorld()
	{
		for (GpuSlot slot : this.gpuSlots)
		{
			if (slot == null || slot.job == null) continue;
			try { this.completeGpuSlot(slot); }
			catch (Throwable failure) { this.disableGpuWorld(failure,null); break; }
		}
		if (this.gpuWorldFailed)
		{
			for (int i = 0; i < CHUNK_ENQUEUE_BUDGET && !this.batchWaiting.isEmpty(); i++)
			{
				ChunkJob job = this.batchWaiting.removeFirst();
				this.pendingChunks.add(job.coord());
				this.chunkJobQueue.add(job);
				this.summaryQueued++;
			}
			return;
		}
		int submitted = 0;
		for (int i = 0; i < this.gpuSlots.length && submitted < CHUNK_ENQUEUE_BUDGET
				&& this.gpuPostPending.get() < GPU_POST_LIMIT && !this.batchWaiting.isEmpty(); i++)
		{
			GpuSlot slot = this.gpuSlots[i];
			if (slot != null && slot.job != null) continue;
			ChunkJob job = this.batchWaiting.removeFirst();
			this.pendingChunks.add(job.coord());
			this.summaryQueued++;
			submitted++;
			if (!job.regionMode()) { this.chunkJobQueue.add(job); continue; }
			try
			{
				if (slot == null)
				{
					GpuCloudGeneration generator = new GpuCloudGeneration(this.chunkBufferPool);
					if (!generator.init()) throw new IllegalStateException("OpenGL GPU generator unavailable");
					slot = new GpuSlot(generator);
					this.gpuSlots[i] = slot;
					if (this.gpuPostPool == null)
					{
						this.gpuPostPool = java.util.concurrent.Executors.newFixedThreadPool(2,r ->
						{
							Thread thread = new Thread(r,"simpleclouds-gpu-post");
							thread.setDaemon(true);
							return thread;
						});
						LOGGER.info("Experimental GPU world generation enabled with {} bounded slots; CPU fallback remains available",GPU_WORLD_SLOTS);
					}
				}
				float wiggle = (job.scrollX()+job.scrollY()+job.scrollZ())/5.0F;
				slot.startedNanos = System.nanoTime();
				slot.generator.submitRegions(job.x0(),job.y0(),job.z0(),job.x1(),job.y1(),job.z1(),
						job.camCloudX(),job.camGridY(),job.camCloudZ(),job.groups(),job.regions(),
						new GpuCloudGeneration.Sampling(job.lodScale(),job.cloudHeight(),
								job.scrollX(),job.scrollY(),job.scrollZ(),wiggle,
								job.transparencyDistance()));
				this.summaryGpuSubmitNanos += System.nanoTime() - slot.startedNanos;
				this.summaryGpuSubmits++;
				slot.job = job;
			}
			catch (Throwable failure) { this.disableGpuWorld(failure,job); return; }
		}
	}

	private void completeGpuSlot(GpuSlot slot)
	{
		ChunkJob job = slot.job;
		boolean countsOnly = this.canFinishGpuWithoutGeometry(job);
		long readbackStarted = System.nanoTime();
		if (!(countsOnly ? slot.generator.pollRegionsCountsOnly() : slot.generator.pollRegions())) return;
		this.summaryGpuReadbackNanos += System.nanoTime() - readbackStarted;
		this.summaryGpuReadbacks++;
		java.nio.ByteBuffer opaque = slot.generator.instanceData();
		java.nio.ByteBuffer transparent = slot.generator.transparentData();
		if (!countsOnly && opaque == null) { opaque = this.chunkBufferPool.borrow(256); opaque.limit(0); }
		if (!countsOnly && transparent == null) { transparent = this.chunkBufferPool.borrow(256); transparent.limit(0); }
		final java.nio.ByteBuffer outputOpaque = opaque, outputTransparent = transparent;
		int opaqueCount = slot.generator.instanceCount(), transparentCount = slot.generator.transparentInstanceCount();
		if (!countsOnly) this.summaryGpuOpaqueBytes += opaque.remaining();
		// The adapter returns six draw records per compact transparent cube.
		if (!countsOnly) this.summaryGpuTransparentBytes += (long) transparentCount / 6L * 24L;
		if (opaqueCount == 0 && transparentCount == 0) this.summaryGpuEmptyChunks++;
		long started = slot.startedNanos;
		GpuOpaqueCandidate directOpaque = null;
		GpuTransparentCandidate directTransparent = null;
		GpuStormColumns.Result gpuStorm = null;
		try
		{
			if (GPU_STORM_BITS_EXPERIMENT)
			{
				if (this.gpuStormBits == null)
					this.gpuStormBits = new dev.nonamecrackers2.simpleclouds.client.renderer.v2.GpuStormColumnBits(32 * 32);
				gpuStorm = this.gpuStormBits.fromFaceIds(slot.generator,job.x0(),job.y0(),job.z0(),
						job.x1(),job.y1(),job.z1(),job.lodScale(),job.camGridY(),job.camCloudX(),job.camCloudZ(),
						job.groups(),job.regions());
			}
			if (GPU_DIRECT_OPAQUE_EXPERIMENT)
				directOpaque = this.makeGpuOpaqueCandidate(job,slot.generator,opaqueCount);
			if (GPU_DIRECT_TRANSPARENT_EXPERIMENT)
				directTransparent = this.makeGpuTransparentCandidate(job,slot.generator,transparentCount);
		}
		catch (RuntimeException | Error failure)
		{
			if (directOpaque != null) directOpaque.close();
			if (directTransparent != null) directTransparent.close();
			this.chunkBufferPool.release(outputOpaque);
			this.chunkBufferPool.release(outputTransparent);
			throw failure;
		}
		final GpuStormColumns.Result reducedStorm = gpuStorm;
		if (countsOnly)
		{
			if (directOpaque == null || !directOpaque.useGpuDelta
					|| directTransparent == null || !directTransparent.useGpuDelta || reducedStorm == null)
			{
				if (directOpaque != null) directOpaque.close();
				if (directTransparent != null) directTransparent.close();
				this.chunkBufferPool.release(outputOpaque);
				this.chunkBufferPool.release(outputTransparent);
				throw new IllegalStateException("Counts-only GPU completion lacks direct draw or storm data");
			}
			boolean accepted;
			synchronized (this.completionLock)
			{
				accepted = !this.workersStopped;
				if (accepted)
					this.completedChunks.add(new ChunkResult(job.coord(),outputOpaque,opaqueCount,
							outputTransparent,transparentCount,reducedStorm.coverage(),reducedStorm.columns(),
							reducedStorm.xCells(),reducedStorm.zCells(),job.scrollX(),job.scrollY(),job.scrollZ(),
							job.regionSig(),job.groupsHash(),job.batch(),System.nanoTime()-started,true,true,true,
								null,null,job));
			}
			if (!accepted)
			{
				directOpaque.close();
				directTransparent.close();
				this.chunkBufferPool.release(outputOpaque);
				this.chunkBufferPool.release(outputTransparent);
				slot.job = null;
				return;
			}
			GpuOpaqueCandidate oldOpaque = this.pendingGpuOpaque.put(job.coord(),directOpaque);
			if (oldOpaque != null) oldOpaque.close();
			GpuTransparentCandidate oldTransparent = this.pendingGpuTransparent.put(job.coord(),directTransparent);
			if (oldTransparent != null) oldTransparent.close();
			slot.job = null;
			if (GPU_TEST_CPU_FALLBACK && ++this.gpuOnlyCompleted == 256)
				this.disableGpuWorld(new IllegalStateException("Intentional development-only GPU-to-CPU fallback test"),null);
			return;
		}
		this.gpuPostPending.incrementAndGet();
		try
		{
			this.gpuPostPool.execute(() ->
			{
				boolean published = false;
				try
				{
					long postStarted = System.nanoTime();
					var cpuStorm = GpuStormColumns.fromFaces(outputOpaque,job.x0(),job.z0(),job.x1(),job.z1(),
							job.lodScale(),job.cloudHeight(),job.camGridY(),job.camCloudX(),job.camCloudZ(),
							job.groups(),job.regions());
					if (reducedStorm != null && (!java.util.Arrays.equals(cpuStorm.columns(),reducedStorm.columns())
							|| Math.abs(cpuStorm.coverage()-reducedStorm.coverage()) > 0.000001f))
						throw new IllegalStateException("GPU storm column bitset differs from CPU face reconstruction");
					var storm = reducedStorm != null ? reducedStorm : cpuStorm;
					var opaqueDelta = dev.nonamecrackers2.simpleclouds.client.renderer.v2.CloudFaceDelta.split(
							this.predecessorFaces(job,false),outputOpaque,CloudVertexFormat.BYTES_PER_INSTANCE);
					var transparentDelta = dev.nonamecrackers2.simpleclouds.client.renderer.v2.CloudFaceDelta.split(
							this.predecessorFaces(job,true),outputTransparent,CloudVertexFormat.BYTES_PER_INSTANCE_ALPHA);
					this.summaryGpuPostNanos.addAndGet(System.nanoTime() - postStarted);
					this.summaryGpuPostJobs.incrementAndGet();
					synchronized (this.completionLock)
					{
						if (!this.workersStopped)
						{
							this.completedChunks.add(new ChunkResult(job.coord(),outputOpaque,opaqueCount,
									outputTransparent,transparentCount,storm.coverage(),storm.columns(),
									storm.xCells(),storm.zCells(),job.scrollX(),job.scrollY(),job.scrollZ(),
									job.regionSig(),job.groupsHash(),job.batch(),System.nanoTime()-started,true,true,false,
									opaqueDelta,transparentDelta,null));
							published = true;
						}
					}
				}
				catch (Throwable failure)
				{
					LOGGER.warn("Experimental GPU chunk post-processing failed; retrying on CPU",failure);
					synchronized (this.completionLock)
					{
						if (!this.workersStopped) this.chunkJobQueue.add(job);
					}
				}
				finally
				{
					if (!published)
					{
						this.chunkBufferPool.release(outputOpaque);
						this.chunkBufferPool.release(outputTransparent);
					}
					this.gpuPostPending.decrementAndGet();
				}
			});
			if (directOpaque != null)
			{
				GpuOpaqueCandidate superseded = this.pendingGpuOpaque.put(job.coord(),directOpaque);
				if (superseded != null) superseded.close();
			}
			if (directTransparent != null)
			{
				GpuTransparentCandidate superseded = this.pendingGpuTransparent.put(job.coord(),directTransparent);
				if (superseded != null) superseded.close();
			}
			slot.job = null;
		}
		catch (RuntimeException rejected)
		{
			this.gpuPostPending.decrementAndGet();
			this.chunkBufferPool.release(outputOpaque);
			this.chunkBufferPool.release(outputTransparent);
			if (directOpaque != null) directOpaque.close();
			if (directTransparent != null) directTransparent.close();
			throw rejected;
		}
	}

	private boolean canFinishGpuWithoutGeometry(ChunkJob job)
	{
		if (!GPU_NO_READBACK_EXPERIMENT || !job.regionMode()) return false;
		ChunkData old = this.chunkCaches.get(job.coord());
		return old == null || old.groupsHash == job.groupsHash()
				&& gpuOpaqueHistoryReady(old) && gpuTransparentHistoryReady(old);
	}

	private static boolean gpuOpaqueHistoryReady(ChunkData old)
	{
		return old.gpuOpaqueRawCount == 0
				? old.gpuResidentOnly || old.opaqueRaw != null && old.opaqueRaw.length == 0
				: old.gpuOpaqueIds != null && old.gpuOpaqueRaw != null;
	}

	private static boolean gpuTransparentHistoryReady(ChunkData old)
	{
		return old.gpuTransparentRawCount == 0
				? old.gpuResidentOnly || old.transparentRaw != null && old.transparentRaw.length == 0
				: old.gpuTransparentIds != null && old.gpuTransparentRaw != null;
	}

	private GpuOpaqueCandidate makeGpuOpaqueCandidate(ChunkJob job,GpuCloudGeneration generator,int count)
	{
		GpuOpaqueCandidate candidate = new GpuOpaqueCandidate(job.batch());
		try
		{
			String name = "simpleclouds.direct." + job.coord().x0() + "." + job.coord().z0()
					+ "." + job.coord().lodScale();
			if (count > 0)
			{
				candidate.raw = RenderSystem.getDevice().createBuffer(() -> name + ".raw",
						GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,(long)count * CloudVertexFormat.BYTES_PER_INSTANCE);
				candidate.ids = RenderSystem.getDevice().createBuffer(() -> name + ".ids",
						GpuBuffer.USAGE_COPY_DST,(long)count * Integer.BYTES);
				generator.copyOpaqueTo(candidate.raw);
				generator.copyFaceIdsTo(candidate.ids);
			}
			candidate.rawCount = count;
			ChunkData old = this.chunkCaches.get(job.coord());
			// IDs are local to a chunk's lattice. A configuration change can move
			// the world-space center or alter vertical dimensions, so use the CPU
			// face matcher for that one replacement and seed new GPU state for later.
			boolean compatible = old == null || old.groupsHash == job.groupsHash() && gpuOpaqueHistoryReady(old);
			if (!compatible) return candidate;
			int oldCount = old == null ? 0 : old.gpuOpaqueRawCount;
			if (this.gpuFaceDelta == null)
				this.gpuFaceDelta = new dev.nonamecrackers2.simpleclouds.client.renderer.v2.GpuFaceDelta(MAX_GPU_FACE_IDS);
			var counts = this.gpuFaceDelta.compare(old == null ? null : old.gpuOpaqueIds,
					old == null ? null : old.gpuOpaqueRaw,oldCount,candidate.ids,candidate.raw,count);
			candidate.stableCount = counts.stable();
			candidate.addedCount = counts.added();
			candidate.removedCount = counts.removed();
			candidate.useGpuDelta = true;
			if (candidate.stableCount > 0)
				candidate.stable = RenderSystem.getDevice().createBuffer(() -> name + ".stable",
						GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
						(long)candidate.stableCount * CloudVertexFormat.BYTES_PER_INSTANCE);
			if (candidate.addedCount > 0)
				candidate.added = RenderSystem.getDevice().createBuffer(() -> name + ".added",
						GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
						(long)candidate.addedCount * CloudVertexFormat.BYTES_PER_INSTANCE);
			if (candidate.removedCount > 0)
				candidate.removed = RenderSystem.getDevice().createBuffer(() -> name + ".removed",
						GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
						(long)candidate.removedCount * CloudVertexFormat.BYTES_PER_INSTANCE);
			this.gpuFaceDelta.copySplitTo(candidate.stable,candidate.added,candidate.removed);
			return candidate;
		}
		catch (RuntimeException | Error failure)
		{
			candidate.close();
			throw failure;
		}
	}

	private GpuTransparentCandidate makeGpuTransparentCandidate(ChunkJob job,GpuCloudGeneration generator,int count)
	{
		GpuTransparentCandidate candidate = new GpuTransparentCandidate(job.batch());
		try
		{
			String name = "simpleclouds.transparentDirect." + job.coord().x0() + "." + job.coord().z0()
					+ "." + job.coord().lodScale();
			if (this.gpuTransparentExpansion == null)
				this.gpuTransparentExpansion = new dev.nonamecrackers2.simpleclouds.client.renderer.v2.GpuTransparentExpansion(32 * 256 * 32);
			int expanded = this.gpuTransparentExpansion.expandFrom(generator,job.cloudHeight());
			if (expanded != count) throw new IllegalStateException("Transparent GPU expansion count differs from readback");
			if (count > 0)
			{
				candidate.raw = RenderSystem.getDevice().createBuffer(() -> name + ".raw",
						GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,(long)count * CloudVertexFormat.BYTES_PER_INSTANCE_ALPHA);
				candidate.ids = RenderSystem.getDevice().createBuffer(() -> name + ".ids",
						GpuBuffer.USAGE_COPY_DST,(long)count * Integer.BYTES);
				this.gpuTransparentExpansion.copyFacesTo(candidate.raw);
				this.gpuTransparentExpansion.copyFaceIdsTo(candidate.ids);
			}
			candidate.rawCount = count;
			ChunkData old = this.chunkCaches.get(job.coord());
			boolean compatible = old == null || old.groupsHash == job.groupsHash() && gpuTransparentHistoryReady(old);
			if (!compatible) return candidate;
			int oldCount = old == null ? 0 : old.gpuTransparentRawCount;
			if (this.gpuTransparentDelta == null)
				this.gpuTransparentDelta = new dev.nonamecrackers2.simpleclouds.client.renderer.v2.GpuFaceDelta(
						MAX_GPU_FACE_IDS,CloudVertexFormat.BYTES_PER_INSTANCE_ALPHA);
			var counts = this.gpuTransparentDelta.compare(old == null ? null : old.gpuTransparentIds,
					old == null ? null : old.gpuTransparentRaw,oldCount,candidate.ids,candidate.raw,count);
			candidate.stableCount = counts.stable();
			candidate.addedCount = counts.added();
			candidate.removedCount = counts.removed();
			candidate.useGpuDelta = true;
			if (candidate.stableCount > 0)
				candidate.stable = RenderSystem.getDevice().createBuffer(() -> name + ".stable",
						GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
						(long)candidate.stableCount * CloudVertexFormat.BYTES_PER_INSTANCE_ALPHA);
			if (candidate.addedCount > 0)
				candidate.added = RenderSystem.getDevice().createBuffer(() -> name + ".added",
						GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
						(long)candidate.addedCount * CloudVertexFormat.BYTES_PER_INSTANCE_ALPHA);
			if (candidate.removedCount > 0)
				candidate.removed = RenderSystem.getDevice().createBuffer(() -> name + ".removed",
						GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
						(long)candidate.removedCount * CloudVertexFormat.BYTES_PER_INSTANCE_ALPHA);
			this.gpuTransparentDelta.copySplitTo(candidate.stable,candidate.added,candidate.removed);
			return candidate;
		}
		catch (RuntimeException | Error failure)
		{
			candidate.close();
			throw failure;
		}
	}

	private void disableGpuWorld(Throwable failure,@Nullable ChunkJob retry)
	{
		LOGGER.warn("Experimental GPU world generation failed; continuing with CPU backend",failure);
		this.gpuWorldFailed = true;
		this.pendingGpuOpaque.values().forEach(GpuOpaqueCandidate::close);
		this.pendingGpuOpaque.clear();
		this.pendingGpuTransparent.values().forEach(GpuTransparentCandidate::close);
		this.pendingGpuTransparent.clear();
		if (this.gpuFaceDelta != null) { this.gpuFaceDelta.close(); this.gpuFaceDelta = null; }
		if (this.gpuTransparentDelta != null) { this.gpuTransparentDelta.close(); this.gpuTransparentDelta = null; }
		if (this.gpuTransparentExpansion != null) { this.gpuTransparentExpansion.close(); this.gpuTransparentExpansion = null; }
		if (this.gpuStormBits != null) { this.gpuStormBits.close(); this.gpuStormBits = null; }
		boolean alreadyQueued = false;
		for (int i = 0; i < this.gpuSlots.length; i++)
		{
			GpuSlot slot = this.gpuSlots[i];
			if (slot == null) continue;
			if (slot.job != null)
			{
				this.chunkJobQueue.add(slot.job);
				if (slot.job == retry) alreadyQueued = true;
			}
			slot.generator.close();
			this.gpuSlots[i] = null;
		}
		if (retry != null && !alreadyQueued) this.chunkJobQueue.add(retry);
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

	/** A3 isolation tool (DevShot OVL0): when false, the overlay passes (shadow
	 *  map + terrain shadows, storm fog, atmospheric layer) are skipped entirely so
	 *  the voxel field alone can be inspected. Dev-only; always true in game. */
	private static volatile boolean overlaysEnabled = true;

	public static void setOverlaysEnabled(boolean enabled)
	{
		overlaysEnabled = enabled;
	}

	/** Step 6 diagnostic (DevShot SHADNEAR): force the shadow start radius (the
	 *  original's MinimumRadius) so near-terrain shadows can be isolated from the
	 *  distance-fade model. -1 = use the real render-distance value. */
	private static volatile float devMinRadius = -1.0F;

	public static void setDevMinRadius(float v)
	{
		devMinRadius = v;
	}

	/** Step 6 diagnostic (DevShot NOFOG): disable the storm-fog fullscreen overlay
	 *  so the terrain cloud-shadow can be isolated from it. */
	private static volatile boolean stormFogEnabled = true;

	public static void setStormFogEnabled(boolean enabled)
	{
		stormFogEnabled = enabled;
	}

	/** Fade-in alpha for one band (step 3): 0 -> 1 at CHUNK_FADE_IN_ALPHA_PER_TICK
	 *  per tick after its data was published; 1.0 once settled. */
	// View bobbing lives only in the pose stack GameRenderer builds for the world, never in
	// camera.getViewRotationMatrix(); MixinGameRenderer hands it over at the tail of bobView so
	// the clouds bob with the terrain instead of swimming against it. Cleared after every use:
	// vanilla does not call bobView when bobbing is off or the camera is not first person, and a
	// stale matrix would bob the clouds on its own.
	private static org.joml.Matrix4f bobbedViewRotation;

	public static void setBobbedViewRotation(org.joml.Matrix4f pose)
	{
		bobbedViewRotation = pose;
	}

	private static org.joml.Matrix4f takeViewRotation(net.minecraft.client.Camera camera)
	{
		// 26.3 applies bob/hurt to the PROJECTION, not the camera view rotation.
		// A bobView pose contains only bobbing (identity while standing still).
		// Using it here replaces yaw/pitch and pins the clouds to the screen.
		bobbedViewRotation = null;
		return camera.getViewRotationMatrix(new org.joml.Matrix4f());
	}

	/**
	 * Vanilla's weather is drawn by the mod, after the clouds.
	 *
	 * <p>The cloud pass runs at the level render tail, after vanilla's weather slot, and the
	 * clouds are opaque - so rain left in that slot is painted over wherever a cloud covers it
	 * and stops dead at every cloud edge. Cancelling the slot alone does not work either: by the
	 * time the mod would draw, vanilla has cleared the state (measured:
	 * {@code rainColumns=0 snowColumns=0 intensity=0.0 radius=0}). So the columns are copied out
	 * while they exist ({@link #captureWeatherState}) and drawn from the copy here.
	 */
	public static boolean redrawsVanillaWeather()
	{
		// OFF: three attempts, none drew anything. Recorded so nobody repeats them.
		//  - Vanilla's weather slot runs BEFORE the cloud pass and the clouds are opaque, so rain
		//    left in that slot is painted over wherever a cloud covers it. That is the bug.
		//  - Cancelling the slot and calling render(state, pass) later draws nothing: vanilla has
		//    cleared the state by then (measured rainColumns=0 snowColumns=0 intensity=0 radius=0).
		//  - Capturing the columns in the mixin and drawing from that copy also draws nothing,
		//    even when only non-empty states are captured. Vanilla calls render several times per
		//    frame and about half carry no columns (measured: 6050 non-empty of 12400 calls), so
		//    an unconditional capture overwrote the good copy - but fixing that changed nothing,
		//    so something besides the columns (the uploaded instance buffer, or state that
		//    prepare depends on) does not survive to the mod draw point.
		// The remaining route is the expensive one: draw the clouds in vanilla cloud slot, which
		// needs every per-draw transform slice computed before the pass opens, because 26.3
		// forbids mapping a buffer while a render pass is open.
		return false;
	}

	/** Vanilla's weather columns for this frame, copied before vanilla clears them. */
	private static final net.minecraft.client.renderer.state.level.WeatherRenderState CAPTURED_WEATHER =
			new net.minecraft.client.renderer.state.level.WeatherRenderState();
	private static boolean capturedWeatherThisFrame;

	public static void captureWeatherState(net.minecraft.client.renderer.state.level.WeatherRenderState state)
	{
		// Vanilla calls render several times a frame and about half of those carry no columns
		// (measured: 6050 of 12400 calls had any). Capturing every call overwrote the good copy
		// with an empty one, so the mod drew nothing - the data was there the whole time.
		if (state.rainColumns.isEmpty() && state.snowColumns.isEmpty())
			return;
		CAPTURED_WEATHER.reset();
		CAPTURED_WEATHER.rainColumns.addAll(state.rainColumns);
		CAPTURED_WEATHER.snowColumns.addAll(state.snowColumns);
		CAPTURED_WEATHER.intensity = state.intensity;
		CAPTURED_WEATHER.radius = state.radius;
		capturedWeatherThisFrame = true;
	}

	private void drawVanillaWeatherAfterClouds(org.joml.Matrix4f view)
	{
		if (!capturedWeatherThisFrame)
			return;
		capturedWeatherThisFrame = false;
		if (CAPTURED_WEATHER.rainColumns.isEmpty() && CAPTURED_WEATHER.snowColumns.isEmpty())
			return;
		var mc = Minecraft.getInstance();
		if (mc.levelRenderer == null || mc.gameRenderer == null)
			return;
		var camera = mc.gameRenderer.mainCamera();
		this.drawPipeline.drawVanillaWeather(mc.levelRenderer.weatherEffectRenderer(), CAPTURED_WEATHER,
				camera.position(), view);
	}



	private float chunkAlpha(ChunkData d, long nowTick, float partialTick)
	{
		double age = nowTick + partialTick - d.lastGenTick;
		return (float) Mth.clamp(age * CHUNK_FADE_IN_ALPHA_PER_TICK, 0.0, 1.0);
	}

	private void generateAndDrawClouds(double camX, double camY, double camZ, float partialTick, Matrix4f projMat)
	{
		// An integrated server sends the authoritative cloud regions and types
		// shortly after join. Drawing the client fallback field before that packet
		// arrives causes a conspicuous full-sky replacement on the first seconds.
		// Keep the client-only/multiplayer fallback, and time out rather than
		// leaving an empty sky if a broken server never sends its packet.
		if (this.mc.hasSingleplayerServer() && this.mc.level != null
				&& CloudManager.get(this.mc.level) instanceof ClientCloudManager clientManager
				&& !clientManager.hasReceivedSync())
		{
			long gameTick = this.mc.level.getGameTime();
			if (this.initialSyncWaitStartedTick == Long.MIN_VALUE)
				this.initialSyncWaitStartedTick = gameTick;
			if (gameTick - this.initialSyncWaitStartedTick < 100)
				return;
			if (!this.initialSyncTimeoutLogged)
			{
				this.initialSyncTimeoutLogged = true;
				LOGGER.warn("Cloud manager did not synchronize within 100 ticks; rendering the client fallback");
			}
		}
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
		this.cullFrustum = null;
		// In 26.3 the level-render hook passes no projection argument. The
		// extracted camera render state owns the active level projection instead.
		Matrix4f activeProjection = projMat != null ? projMat
				: Minecraft.getInstance().gameRenderer.gameRenderState()
						.levelRenderState.cameraRenderState.projectionMatrix;
		if (SimpleCloudsConfig.CLIENT.frustumCulling.get() && activeProjection != null)
		{
			// Frustum.prepare takes the camera origin in world blocks; pass only
			// the view rotation here, not a view matrix already translated by it.
			this.cullFrustum = new Frustum(takeViewRotation(camera), activeProjection);
			this.cullFrustum.prepare(camX, camY, camZ);
		}
		org.joml.Matrix4f view = new org.joml.Matrix4f();
		view.mul(takeViewRotation(camera));
		view.mul(new org.joml.Matrix4f().translate((float)-camX, (float)-camY, (float)-camZ));

		// Scroll/Wiggle alter noise samples, not the world-space draw transform.
		// This matches the original renderer's camera/cloud-height-only transform.
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
		int groupsHash = 31 * groups.hashCode() + Boolean.hashCode(this.usesRegionMasks());
		// Formation footprints: the original's multi-region path masks the same
		// world-fixed noise field by the spawned formations' X/Z coverage. With no
		// formations (not synced yet / vanilla weather) the generator falls back to
		// the infinite field.
		java.util.Map<net.minecraft.resources.Identifier, Integer> typeToGroup = dataDrivenGroupIndices();
		// Capture actual interpolated transforms; per-chunk keys quantize their
		// influence at that chunk's sampling resolution. No global signature.
		List<CpuCloudGenerator.RegionMask> regions = List.copyOf(this.buildRegionMasks(typeToGroup, partialTick));
		LevelOfDetailOptions selectedLod = SimpleCloudsConfig.CLIENT.levelOfDetail.get();
		boolean layoutChanged = this.lodConfig != selectedLod.getConfig();
		if (layoutChanged)
		{
			this.lodConfig = selectedLod.getConfig();
			this.lodChunks = this.lodConfig.getPreparedChunks();
			LOGGER.info("Cloud LOD layout changed to {} ({} prepared chunks)", selectedLod, this.lodChunks.size());
		}
		String previousGridKey = this.lastChunkGridKey;
		boolean chunkSetChanged = layoutChanged || !gridKey.equals(previousGridKey);
		this.lastChunkGridKey = gridKey;

		// Keep one Y lattice for every selected cloud type. Deriving this span from
		// only the currently spawned formations invalidated the entire 364-chunk
		// field whenever a taller/shorter type appeared or disappeared.
		// Round up so every LOD grid divides the same stable vertical extent.
		int maxLayerY = LOD_Y_MIN;
		int[] groupMaxY = new int[groups.size()];
		for (int gi = 0; gi < groups.size(); gi++)
		{
			int groupY = LOD_Y_MIN;
			for (CpuCloudGenerator.NoiseLayer l : groups.get(gi).layers())
				groupY = Math.max(groupY, (int) Math.ceil(l.heightOffset() + l.height()));
			groupMaxY[gi] = (int) (Math.ceil(groupY / 8.0) * 8);
			maxLayerY = Math.max(maxLayerY, groupMaxY[gi]);
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
		java.util.Set<ChunkCoord> targetKeys = new java.util.HashSet<>();
		for (long[] c : target) targetKeys.add(new ChunkCoord((int)c[0], (int)c[1], (int)c[2]));
		if (chunkSetChanged)
		{
			int dx = snapX - this.cachedSnapX, dz = snapZ - this.cachedSnapZ;
			if (WORLD_COVERAGE)
			{
				java.util.Map<ChunkCoord, CloudWorldCoverage.Rect> rectangles = new java.util.HashMap<>();
				for (ChunkCoord coord : targetKeys) rectangles.put(coord, worldBounds(coord));
				this.worldCoverage.retarget(rectangles);
				this.chunkCaches.keySet().retainAll(targetKeys);
			}
			else dev.nonamecrackers2.simpleclouds.client.renderer.v2.CloudGridTransition.retarget(
					this.chunkCaches, this.retainedGridChunks, targetKeys,
					coord -> layoutChanged ? null : new ChunkCoord(coord.x0() - dx, coord.z0() - dz, coord.lodScale()),
					SimpleCloudsRenderer::closeChunk);
			this.cachedSnapX = snapX;
			this.cachedSnapZ = snapZ;
			if (TRACE_VISUAL_CHURN)
				LOGGER.info("[MOTION-RETAIN] tick={} exact={} retained={} target={} worldFragments={}",
						mc.level.getGameTime(), this.chunkCaches.size(), this.retainedGridChunks.size(), target.size(),
						WORLD_COVERAGE ? this.worldCoverage.snapshot().values().stream().mapToInt(List::size).sum() : 0);
		}
		if (TRACE_VISUAL_CHURN && chunkSetChanged)
		{
			int overlap = 0;
			for (ChunkCoord coord : targetKeys)
				if (this.chunkCaches.containsKey(coord)) overlap++;
			LOGGER.info("[MOTION-GRID] tick={} previous={} grid={} camera={},{},{} layoutChanged={} target={} cached={} overlap={} missing={}",
					mc.level.getGameTime(), previousGridKey, gridKey, camX, camY, camZ,
					layoutChanged, target.size(), this.chunkCaches.size(), overlap, target.size() - overlap);
		}
		int transparencyDistance = CpuCloudGenerator.transparencyDistance(
				this.lodConfig.getEffectiveChunkSpan(), PRIMARY_CHUNK,
				SimpleCloudsConfig.CLIENT.transparencyRenderDistancePercentage.get());
		// Camera height only changes the storm-above-camera summary, not any
		// cloud voxel or face. Including its bucket here made head bob near a
		// 64-block boundary invalidate the entire field repeatedly.
		int generationConfig = java.util.Objects.hash(groupsHash, maxLayerY, cloudHeight,
				transparencyDistance);
		if (generationConfig != this.lastLoggedGenerationConfig)
		{
			LOGGER.info("Cloud generation config changed: groupsHash={} maxLayerY={} cloudHeight={} cameraYBucket={} transparencyDistance={} groups={}",
					groupsHash,maxLayerY,cloudHeight,Math.floorDiv(camGridY,8),transparencyDistance,groups.size());
			this.lastLoggedGenerationConfig = generationConfig;
		}
		boolean paused = mc.isPaused();
		if (!paused)
			this.prepareBatch(target, groups, regions, generationConfig, maxLayerY, groupMaxY,
					camGridY, cloudHeight,
					scrollX, scrollY, scrollZ, (float)camCloudX, (float)camCloudZ,
					transparencyDistance, this.cullFrustum);

		// Pick up finished chunks (budgeted, generated off-thread — no render hitch).
		// Results retain the exact key/config/phase with which they were generated.
		// Each completed chunk is published independently. The worker has already
		// split its faces into stable, added and removed sets before this GPU upload.
		int polled = 0;
		int replacedThisFrame = 0;
		long changedFacesThisFrame = 0;
		ChunkResult result;
		while (!paused && polled < (GPU_WORLD_EXPERIMENT ? CHUNK_POLL_BUDGET : CPU_POLL_BUDGET)
				&& (result = this.completedChunks.poll()) != null)
		{
			polled++;
			final ChunkResult r = result; // final capture for the buffer-name lambdas
			GpuOpaqueCandidate gpuOpaque = this.pendingGpuOpaque.get(r.coord());
			if (gpuOpaque != null)
			{
				if (gpuOpaque.batch == r.batch()) this.pendingGpuOpaque.remove(r.coord());
				else gpuOpaque = null;
			}
			GpuTransparentCandidate gpuTransparent = this.pendingGpuTransparent.get(r.coord());
			if (gpuTransparent != null)
			{
				if (gpuTransparent.batch == r.batch()) this.pendingGpuTransparent.remove(r.coord());
				else gpuTransparent = null;
			}
			this.pendingChunks.remove(r.coord());
			if (r.batch() != this.batchId)
			{
				if (gpuOpaque != null) gpuOpaque.close();
				if (gpuTransparent != null) gpuTransparent.close();
				this.chunkBufferPool.release(r.opaque());
				this.chunkBufferPool.release(r.transparent());
				continue;
			}
			// A second crossing may change the retained predecessor while this
			// worker runs. Its face delta is then stale even if its world key matches.
			// Keep the drawable predecessor and retry; never apply an unrelated delta.
			if (targetKeys.contains(r.coord())
					&& (!WORLD_COVERAGE
						? this.batchDeltaSources.get(r.coord()) != this.drawableChunk(r.coord())
						: !java.util.Objects.equals(this.batchWorldSources.get(r.coord()), this.worldCoverage.fragments(r.coord()))))
			{
				if (gpuOpaque != null) gpuOpaque.close();
				if (gpuTransparent != null) gpuTransparent.close();
				this.chunkBufferPool.release(r.opaque());
				this.chunkBufferPool.release(r.transparent());
				this.batchExpected.remove(r.coord());
				this.batchDeltaSources.remove(r.coord());
				this.batchWorldSources.remove(r.coord());
				this.batchFailed = true;
				if (TRACE_VISUAL_CHURN) LOGGER.info("[MOTION-STALE-DELTA] coord={} batch={}", r.coord(), r.batch());
				continue;
			}
			if (r.gpuOnly() && (gpuOpaque == null || !gpuOpaque.useGpuDelta
					|| gpuTransparent == null || !gpuTransparent.useGpuDelta))
			{
				// GPU failure may have closed candidates after this result was
				// queued. Preserve the expected chunk and regenerate it on CPU.
				if (gpuOpaque != null) gpuOpaque.close();
				if (gpuTransparent != null) gpuTransparent.close();
				this.chunkBufferPool.release(r.opaque());
				this.chunkBufferPool.release(r.transparent());
				if (r.retryJob() == null) throw new IllegalStateException("GPU-only result has no CPU retry job");
				this.pendingChunks.add(r.coord());
				this.chunkJobQueue.add(r.retryJob());
				this.summaryQueued++;
				this.summaryGpuDirectFallback++;
				continue;
			}
			this.batchExpected.remove(r.coord());
			this.batchDeltaSources.remove(r.coord());
			this.batchWorldSources.remove(r.coord());
			this.summaryCompleted++;
			this.summaryGenerationNanos += r.generationNanos();
			if (!r.success())
			{
				if (gpuOpaque != null) gpuOpaque.close();
				if (gpuTransparent != null) gpuTransparent.close();
				this.batchFailed = true;
				this.retryBatchAfterNanos = System.nanoTime() + 2_000_000_000L;
				this.summaryFailed++;
				continue;
			}
			ChunkData d = new ChunkData();
			ChunkData fadedOut = new ChunkData();
			try
			{
			d.regionSig = r.regionSig();
			d.sourceCoord = r.coord();
			d.groupsHash = r.groupsHash();
			d.lastGenTick = mc.level.getGameTime();
			ChunkData previous = this.drawableChunk(r.coord());
			var opaqueDelta = r.opaqueDelta();
			var transparentDelta = r.transparentDelta();
			String bufferName = "simpleclouds.chunk." + r.coord().x0() + "." + r.coord().z0() + "." + r.coord().lodScale();
			long uploadStarted = System.nanoTime();
			boolean directOpaque = r.gpuOnly();
			boolean directTransparent = r.gpuOnly();
			if (r.gpuOnly())
			{
				if (!r.fromGpu() || gpuOpaque == null || !gpuOpaque.useGpuDelta
						|| gpuTransparent == null || !gpuTransparent.useGpuDelta)
					throw new IllegalStateException("Counts-only GPU result has no valid direct draw buffers");
				d.gpuResidentOnly = true;
				gpuOpaque.adopt(d,fadedOut);
				gpuTransparent.adopt(d,fadedOut);
				this.summaryGpuDirectOpaque++;
				this.summaryGpuDirectTransparent++;
			}
			else
			{
			d.opaqueRaw = opaqueDelta.complete();
			d.transparentRaw = transparentDelta.complete();
			directOpaque = r.fromGpu() && gpuOpaque != null && gpuOpaque.useGpuDelta
					&& gpuOpaque.stableCount == opaqueDelta.stableCount(CloudVertexFormat.BYTES_PER_INSTANCE)
					&& gpuOpaque.addedCount == opaqueDelta.addedCount(CloudVertexFormat.BYTES_PER_INSTANCE)
					&& gpuOpaque.removedCount == opaqueDelta.removedCount(CloudVertexFormat.BYTES_PER_INSTANCE);
			if (r.fromGpu() && gpuOpaque != null && gpuOpaque.useGpuDelta && !directOpaque)
				LOGGER.warn("GPU face-local delta counts disagree with CPU for {}; using CPU draw buffers",r.coord());
			if (directOpaque) this.summaryGpuDirectOpaque++;
			else if (r.fromGpu() && gpuOpaque != null) this.summaryGpuDirectFallback++;
			if (directOpaque)
				gpuOpaque.adopt(d,fadedOut);
			else
			{
				if (r.fromGpu() && gpuOpaque != null) gpuOpaque.adoptRawOnly(d);
				d.opaqueCount = opaqueDelta.stableCount(CloudVertexFormat.BYTES_PER_INSTANCE);
				d.opaque = uploadFaces(bufferName + ".stable.o", opaqueDelta.stable());
				d.opaqueAddedCount = opaqueDelta.addedCount(CloudVertexFormat.BYTES_PER_INSTANCE);
				d.opaqueAdded = uploadFaces(bufferName + ".added.o", opaqueDelta.added());
				fadedOut.opaqueCount = opaqueDelta.removedCount(CloudVertexFormat.BYTES_PER_INSTANCE);
				fadedOut.opaque = uploadFaces(bufferName + ".removed.o", opaqueDelta.removed());
			}
			directTransparent = r.fromGpu() && gpuTransparent != null && gpuTransparent.useGpuDelta
					&& gpuTransparent.stableCount == transparentDelta.stableCount(CloudVertexFormat.BYTES_PER_INSTANCE_ALPHA)
					&& gpuTransparent.addedCount == transparentDelta.addedCount(CloudVertexFormat.BYTES_PER_INSTANCE_ALPHA)
					&& gpuTransparent.removedCount == transparentDelta.removedCount(CloudVertexFormat.BYTES_PER_INSTANCE_ALPHA);
			if (r.fromGpu() && gpuTransparent != null && gpuTransparent.useGpuDelta && !directTransparent)
				LOGGER.warn("GPU transparent face-local delta counts disagree with CPU for {}; using CPU draw buffers",r.coord());
			if (directTransparent) this.summaryGpuDirectTransparent++;
			else if (r.fromGpu() && gpuTransparent != null) this.summaryGpuTransparentFallback++;
			if (directTransparent)
				gpuTransparent.adopt(d,fadedOut);
			else
			{
				if (r.fromGpu() && gpuTransparent != null) gpuTransparent.adoptRawOnly(d);
				d.transparentCount = transparentDelta.stableCount(CloudVertexFormat.BYTES_PER_INSTANCE_ALPHA);
				d.transparent = uploadFaces(bufferName + ".stable.t", transparentDelta.stable());
				d.transparentAddedCount = transparentDelta.addedCount(CloudVertexFormat.BYTES_PER_INSTANCE_ALPHA);
				d.transparentAdded = uploadFaces(bufferName + ".added.t", transparentDelta.added());
				fadedOut.transparentCount = transparentDelta.removedCount(CloudVertexFormat.BYTES_PER_INSTANCE_ALPHA);
				fadedOut.transparent = uploadFaces(bufferName + ".removed.t", transparentDelta.removed());
			}
			this.summaryUploadNanos += System.nanoTime() - uploadStarted;
			this.summaryUploads++;
			}
			d.stormCoverage = r.stormCoverage();
			d.stormColumns = r.stormColumns();
			d.stormXCells = r.stormXCells();
			d.stormZCells = r.stormZCells();
			d.genScrollX = r.scrollX();
			d.genScrollY = r.scrollY();
			d.genScrollZ = r.scrollZ();
			// Unchanged faces are drawn without a transition. Only genuinely added
			// and removed faces take part in the complementary five-tick dissolve.
			if (targetKeys.contains(r.coord()))
			{
				// A CPU fallback after a counts-only GPU generation has no heap
				// previous-face copy. Fade its complete GPU-resident field out
				// instead of dropping it abruptly while the CPU result fades in.
				if (previous != null && previous.gpuResidentOnly)
				{
					if (!directOpaque && previous.gpuOpaqueRaw != null)
					{
						if (fadedOut.opaque != null) fadedOut.opaque.close();
						fadedOut.opaque = previous.gpuOpaqueRaw;
						fadedOut.opaqueCount = previous.gpuOpaqueRawCount;
						previous.gpuOpaqueRaw = null;
					}
					if (!directTransparent && previous.gpuTransparentRaw != null)
					{
						if (fadedOut.transparent != null) fadedOut.transparent.close();
						fadedOut.transparent = previous.gpuTransparentRaw;
						fadedOut.transparentCount = previous.gpuTransparentRawCount;
						previous.gpuTransparentRaw = null;
					}
				}
				if (WORLD_COVERAGE)
				{
					d.departing = fadedOut;
					this.worldCoverage.publish(r.coord(), d);
					this.chunkCaches.put(r.coord(), d);
					if (GPU_TEST_CPU_FALLBACK && r.fromGpu() && !r.gpuOnly()
							&& ++this.gpuOnlyCompleted == 512)
					{
						var beforeFallback = this.worldCoverage.snapshot();
						this.disableGpuWorld(new IllegalStateException("Intentional development-only GPU-readback-to-CPU fallback test"), null);
						if (!beforeFallback.equals(this.worldCoverage.snapshot()))
							throw new IllegalStateException("GPU fallback changed live physical coverage");
						LOGGER.info("[GPU-FALLBACK-TEST] readbackCompletions=512 coverageUnchanged=true fragments={}",
								beforeFallback.values().stream().mapToInt(List::size).sum());
					}
				}
				else
				{
				this.chunkCaches.put(r.coord(), d);
				this.retainedGridChunks.remove(r.coord());
				closeChunk(previous);
				if (fadedOut.opaque != null || fadedOut.transparent != null)
				{
					ChunkData superseded = this.previousBatch.put(r.coord(), fadedOut);
					if (TRACE_VISUAL_CHURN && superseded != null && previous != null)
					{
						this.summarySupersededFades++;
						float priorAlpha = chunkAlpha(previous, mc.level.getGameTime(), 0.0F);
						if (priorAlpha < 1.0F)
						{
							this.summaryInterruptedFades++;
							if (this.summaryInterruptedFades <= 8)
								LOGGER.info("[FADE-OVERLAP] tick={} coord={} priorAlpha={} discardedFaces={}",
										mc.level.getGameTime(), r.coord(), priorAlpha,
										superseded.opaqueCount + superseded.transparentCount);
						}
					}
					closeChunk(superseded);
				}
				else
				{
					closeChunk(this.previousBatch.remove(r.coord()));
				}
				}
			}
			else { closeChunk(d); closeChunk(fadedOut); }
			if (!r.gpuOnly())
				this.summaryUploadBytes += opaqueDelta.stable().length + opaqueDelta.added().length + opaqueDelta.removed().length
						+ transparentDelta.stable().length + transparentDelta.added().length + transparentDelta.removed().length;
			if (previous != null && targetKeys.contains(r.coord()))
			{
				replacedThisFrame++;
				changedFacesThisFrame += r.gpuOnly()
						? (long) gpuOpaque.addedCount + gpuOpaque.removedCount
								+ gpuTransparent.addedCount + gpuTransparent.removedCount
						: (long) opaqueDelta.addedCount(CloudVertexFormat.BYTES_PER_INSTANCE)
						+ opaqueDelta.removedCount(CloudVertexFormat.BYTES_PER_INSTANCE)
						+ transparentDelta.addedCount(CloudVertexFormat.BYTES_PER_INSTANCE_ALPHA)
						+ transparentDelta.removedCount(CloudVertexFormat.BYTES_PER_INSTANCE_ALPHA);
			}
			}
			catch (RuntimeException uploadFailure)
			{
				// publish commits coverage before retiring unused owners. If retirement
				// throws, the new source is already live: never close its draw buffers.
				boolean published = WORLD_COVERAGE && this.worldCoverage.fragments(r.coord())
						.stream().anyMatch(piece -> piece.source() == d);
				if (published)
					this.chunkCaches.put(r.coord(), d);
				else
				{
					// d may own fadedOut already; release it through exactly one path.
					boolean ownsFade = d.departing == fadedOut;
					closeChunk(d);
					if (!ownsFade) closeChunk(fadedOut);
				}
				this.batchFailed = true;
				this.summaryFailed++;
				this.retryBatchAfterNanos = System.nanoTime() + 2_000_000_000L;
				LOGGER.warn(published
						? "Simple Clouds chunk retirement failed after publication; keeping the new chunk"
						: "Simple Clouds chunk upload failed; retaining the previous chunk", uploadFailure);
			}
			finally
			{
			if (gpuOpaque != null) gpuOpaque.close();
			if (gpuTransparent != null) gpuTransparent.close();
			// A1: the CPU copies are released back to the pool now that the GPU has them.
			this.chunkBufferPool.release(r.opaque());
			this.chunkBufferPool.release(r.transparent());
			}
		}
		if (TRACE_VISUAL_CHURN)
		{
			this.summaryChangedFaces += changedFacesThisFrame;
			this.summaryMaxChangedFacesInFrame = Math.max(this.summaryMaxChangedFacesInFrame, changedFacesThisFrame);
			this.summaryMaxReplacedInFrame = Math.max(this.summaryMaxReplacedInFrame, replacedThisFrame);
			if (replacedThisFrame >= 6 && changedFacesThisFrame >= 3000)
				LOGGER.info("[VISUAL-CHURN] tick={} batch={} replacedChunks={} changedFaces={} targetChunks={} cachedChunks={} scroll={},{},{}",
						mc.level.getGameTime(), this.batchId, replacedThisFrame, changedFacesThisFrame,
						targetKeys.size(), this.chunkCaches.size(), scrollX, scrollY, scrollZ);
		}
		if (!paused && this.batchInProgress && this.batchExpected.isEmpty())
		{
			this.batchInProgress = false;
			if (!this.batchFailed)
			{
				this.publishedBatchCount++;
				long publishedAt = System.nanoTime();
				long batchDuration = publishedAt - this.batchStartedNanos;
				this.summaryPublishedBatches++;
				this.summaryBatchDurationNanos += batchDuration;
				this.summaryLongestBatchNanos = Math.max(this.summaryLongestBatchNanos, batchDuration);
				if (this.lastPublishedNanos != 0)
					this.summaryLongestPublishGapNanos = Math.max(this.summaryLongestPublishGapNanos, publishedAt - this.lastPublishedNanos);
				this.lastPublishedNanos = publishedAt;
				this.phaseX = this.batchX; this.phaseY = this.batchY; this.phaseZ = this.batchZ;
				this.hasScrollPhase = true;
			}
		}
		// A1: hard cap on cached chunks (safety net; frees the GPU buffers of any
		// leaked entries).
		if (!WORLD_COVERAGE && this.chunkCaches.size() > MAX_CACHED_CHUNKS)
		{
			java.util.Iterator<java.util.Map.Entry<ChunkCoord, ChunkData>> it = this.chunkCaches.entrySet().iterator();
			while (this.chunkCaches.size() > MAX_CACHED_CHUNKS && it.hasNext())
			{
				ChunkData leaked = it.next().getValue();
				it.remove();
				closeChunk(leaked);
			}
		}

		if (!paused && GPU_WORLD_EXPERIMENT) this.serviceGpuWorld();
		else for (int i = 0; !paused && i < CPU_ENQUEUE_BUDGET && !this.batchWaiting.isEmpty(); i++)
		{
			ChunkJob job = this.batchWaiting.removeFirst();
			this.pendingChunks.add(job.coord());
			this.chunkJobQueue.add(job);
			this.summaryQueued++;
		}
		this.logGenerationSummary();
		// Filling the initial 364-chunk layout used to be visible as several
		// large sky replacements. Prepare it out of view, then reveal the ready
		// field over five game ticks. This is startup-only; later voxel updates
		// still publish individually through the normal face-delta path.
		if (!this.initialFieldRevealed)
		{
			long gameTick = mc.level.getGameTime();
			if (this.initialFieldWaitStartedTick == Long.MIN_VALUE)
				this.initialFieldWaitStartedTick = gameTick;
			if (this.getChunkFillFraction() < 0.97F
					&& gameTick - this.initialFieldWaitStartedTick < 200)
				return;
			if (this.getChunkFillFraction() < 0.97F)
				LOGGER.warn("Initial cloud field reached only {} fill after 200 ticks; revealing the available chunks",
						this.getChunkFillFraction());
			this.initialFieldRevealed = true;
			this.initialFieldRevealTick = gameTick;
		}

		float localStorm = 0.0F;
		int totalOpaque = 0, totalTransp = 0;
		if (!paused)
			this.lastUnpausedGameTick = mc.level.getGameTime();
		long nowTick = this.lastUnpausedGameTick;
		float startupAlpha = Mth.clamp((nowTick + partialTick - this.initialFieldRevealTick)
				* CHUNK_FADE_IN_ALPHA_PER_TICK, 0.0F, 1.0F);
		// A paused single-player world keeps gameTime still, so voxel dissolves
		// freeze with the world instead of finishing on a wall-clock timer.
		var fading = this.previousBatch.entrySet().iterator();
		while (fading.hasNext())
		{
			var entry = fading.next();
			ChunkData current = this.chunkCaches.get(entry.getKey());
			if (!targetKeys.contains(entry.getKey()) || current == null
					|| chunkAlpha(current, nowTick, partialTick) >= 1.0F)
			{
				closeChunk(entry.getValue());
				fading.remove();
			}
		}
		// Plan item 3: where storm cloud is overhead around the camera, for the spatial storm
		// fog. Columns and rendered geometry share the same world-fixed lattice.
		this.stormFogMap.begin(camX, camZ);
		for (long[] c : target)
		{
			ChunkCoord coord = new ChunkCoord((int)c[0], (int)c[1], (int)c[2]);
			for (var piece : this.drawablePieces(coord))
			{
			ChunkData d = piece.source();
			ChunkCoord origin = d.sourceCoord;
			CloudWorldCoverage.Rect clip = WORLD_COVERAGE ? piece.bounds() : null;
			localStorm += !WORLD_COVERAGE ? d.stormCoverage
					: dev.nonamecrackers2.simpleclouds.client.renderer.v2.StormCoverage.contribution(
							d.stormColumns, d.stormXCells, d.stormZCells, origin.x0(), origin.z0(), origin.lodScale(),
							(float)camCloudX, (float)camCloudZ, clip);
			if (d.stormColumns != null)
				this.stormFogMap.addChunk(d.stormColumns, d.stormXCells, d.stormZCells,
						origin.x0() * CLOUD_SCALE_F, origin.z0() * CLOUD_SCALE_F,
						origin.lodScale() * CLOUD_SCALE_F, clip);
			totalOpaque += d.opaqueCount + d.opaqueAddedCount;
			totalTransp += d.transparentCount + d.transparentAddedCount;
			}
		}
		// Evict chunks that left the target set (keeps the map bounded; A1: their
		// GPU buffers are freed here — "free GPU buffers of chunks that leave the
		// LOD layout").
		if (chunkSetChanged || polled > 0)
		{
			int evictedCount = 0;
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
					if (!WORLD_COVERAGE) closeChunk(evicted);
					it.remove();
					evictedCount++;
				}
			}
			if (TRACE_VISUAL_CHURN && evictedCount > 0)
				LOGGER.info("[MOTION-EVICT] tick={} grid={} evicted={} retained={} target={} polled={}",
						mc.level.getGameTime(), gridKey, evictedCount, this.chunkCaches.size(), target.size(), polled);
		}
		this.cacheStormCoverage = Mth.clamp(localStorm, 0.0F, 1.0F);

		// A1: no combined buffer, no per-frame rebuild. The per-chunk GPU buffers are
		// persistent (created when a chunk is published); a one-shot stats line
		// replaces the old per-rebuild log.
		if (totalOpaque > 0 && !this.loggedFieldStats)
		{
			this.loggedFieldStats = true;
			LOGGER.info("Simple Clouds clouds: {} chunks -> {} opaque / {} transparent instances (cam {}x{}x{}, snap {}x{})",
					this.chunkCaches.size(), totalOpaque, totalTransp, camX, camY, camZ, snapX, snapZ);
		}

		// A3 (VISUAL-PARITY-PLAN): the ORIGINAL's cloud fog is based on the FIELD
		// radius, not the vanilla render distance: fogStart = fieldRadius*8 / 4,
		// fogEnd = fieldRadius*8, floored at 2867 blocks (SimpleCloudsRenderer 1.20.1:
		// "fogStart = renderDistance/4, fogEnd = renderDistance" with renderDistance =
		// max(cloudAreaMaxRadius*CLOUD_SCALE, 2867); HIGH layout = 1280 cloud units =
		// 10240 blocks). The clouds therefore stay white out to a quarter of the field
		// and only fade at its edge. (Step 3's render-distance-relative fog — 192..576
		// blocks at RD 12 — fogged two thirds of the field into the flat grey horizon
		// layer and the "grey haze" overhead view: the A3 headline bugs.)
		float fieldRadiusBlocks = (this.lodConfig != null
				? this.lodConfig.getEffectiveChunkSpan() * PRIMARY_CHUNK / 2
				: 1280) * CLOUD_SCALE_F;
		if (fieldRadiusBlocks < 2867.0F)
			fieldRadiusBlocks = 2867.0F;
		float fogStart = fieldRadiusBlocks / 4.0F;
		float fogEnd = fieldRadiusBlocks;
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
		this.fogStart = fogStart;
		this.fogEnd = fogEnd;
		this.drawPipeline.setFog(fr, fg, fb, fogStart, fogEnd);

		// A1: per-chunk passes from the persistent per-chunk GPU buffers (the
		// original's architecture: each mesh chunk draws its own buffer). The fade-in
		// alpha (step 3) is just this chunk's pass ColorModulator — no separate fade
		// uploads, no whole-field rebuild.
		boolean transpPassEnabled = SimpleCloudsConfig.CLIENT.transparency.get();
		for (long[] c : target)
		{
			ChunkCoord coord = new ChunkCoord((int)c[0], (int)c[1], (int)c[2]);
			for (var piece : this.drawablePieces(coord)) this.drawPiece(view, piece.source(),
					WORLD_COVERAGE ? piece.source().departing : this.previousBatch.get(coord),
					WORLD_COVERAGE ? piece.bounds() : null, false, startupAlpha, nowTick, partialTick);
		}
		// Transparent edges after the opaque pass (same chunk order; far-to-near
		// blending order is a step-5 concern).
		if (transpPassEnabled)
		{
			for (long[] c : target)
			{
				ChunkCoord coord = new ChunkCoord((int)c[0], (int)c[1], (int)c[2]);
				for (var piece : this.drawablePieces(coord)) this.drawPiece(view, piece.source(),
						WORLD_COVERAGE ? piece.source().departing : this.previousBatch.get(coord),
						WORLD_COVERAGE ? piece.bounds() : null, true, startupAlpha, nowTick, partialTick);
			}
		}

		// World effects: custom rain (1.20.1 PrecipitationQuads; slice without
		// wind tilt / snow) into the scene, before the overlays.
		if (SimpleCloudsConfig.CLIENT.renderCustomRain.get())
		{
			this.getWorldEffectsManager().renderRain(view, partialTick, camX, camY, camZ);
		}

		// Lightning is NOT gated on custom rain - a bolt is not precipitation. The braces
		// are here because the old indentation said otherwise while the code did this.
		if (this.getWorldEffectsManager().hasLightningToRender())
			this.getWorldEffectsManager().renderLightning(view, partialTick, camX, camY, camZ, this.drawPipeline);

		// Storm fog (plan item 3): ray-marched through the spatial coverage map, so only view
		// rays under storm cover darken, and lit locally by nearby bolts (no global lift: the
		// old LightningMul brightened the whole fog for every strike).
		if (stormFogEnabled && overlaysEnabled && SimpleCloudsConfig.CLIENT.renderStormFog.get())
		{
			boolean boltLight = devFogFlashes && SimpleCloudsConfig.CLIENT.stormFogLightningFlashes.get()
					&& !this.mc.options.hideLightningFlash().get();
			float fogMax = this.getFogEnd() > 0.0F
					? Math.min(this.getFogEnd(), dev.nonamecrackers2.simpleclouds.client.renderer.v2.StormFogMap.MAX_FOG_DISTANCE)
					: dev.nonamecrackers2.simpleclouds.client.renderer.v2.StormFogMap.MAX_FOG_DISTANCE;
			this.collectFogBolts(camX, camY, camZ, partialTick, fogMax, boltLight);
			if (this.stormFogMap.hasCoverage() || devStormFogDebug > 0)
				this.drawPipeline.drawStormFog(view, camX, camY, camZ, cloudHeight, fogMax, this.stormFogMap,
						this.fogBolts, this.fogBoltCount, devStormFogDebug);
		}

		// Sky flash (storm plan step 1): the vanilla 26.2 sky flash is dead (nothing
		// consumes ClientLevel.getSkyFlashTime), so the port draws its own short
		// full-screen white brightening on the same gated strength — visible only
		// while a rendered bolt is within 2000 blocks and bright, never for far
		// strikes, and never with "Hide Sky Flashes" on.
		// No full-screen sky flash: the original brightens the CLOUDS and nothing else (see
		// CloudsDrawPipeline.setFlashBoost). Flashing every sky pixel lit up a clear sky when the
		// only storm was over a kilometre away.

		// Cloud shadows (26.2 slice): top-down ortho depth pass over the cloud
		// instances, then a fullscreen terrain-shadow pass (see CloudShadowPass
		// section of CloudsDrawPipeline and PORTING.md).
		// A1: the shadow map draws the per-chunk buffers (one drawIndexed per chunk
		// inside a single pass).
		this.shadowSources.clear();
		for (long[] c : target)
		{
			for (var piece : this.drawablePieces(new ChunkCoord((int)c[0], (int)c[1], (int)c[2])))
			{
				ChunkData d = piece.source();
				if (d.opaque != null) this.shadowSources.add(new CloudsDrawPipeline.InstanceSource(d.opaque,
						d.opaqueCount, WORLD_COVERAGE ? piece.bounds() : null));
			}
		}
		this.drawPipeline.renderCloudShadowMap(camX, camY, camZ, (float) cloudHeight, this.shadowSources);
		if (overlaysEnabled)
		{
			// Step 6: the original's MinimumRadius — the render distance in blocks
			// (cloud_shadows shadows start at render distance + 32).
			float minimumRadius = mc.options.getEffectiveRenderDistance() * 16.0F;
			if (devMinRadius >= 0.0F) // SHADNEAR diagnostic
				minimumRadius = devMinRadius;
			this.drawPipeline.drawTerrainShadows(terrainView, camX, camY, camZ, (float) cloudHeight, minimumRadius);

		// LAST: vanilla's rain and snow, over the clouds and every overlay above them.
		if (redrawsVanillaWeather())
			this.drawVanillaWeatherAfterClouds(view);
		}

		// Atmospheric (high cirrus-type) clouds: biome-driven 2D layer over the
		// whole view (original: end of the DefaultPipeline render).
		if (overlaysEnabled && SimpleCloudsConfig.CLIENT.atmosphericClouds.get() && this.atmosphericClouds != null)
		{
			// 26.2: the level projection's vertical FOV lives on the camera render
			// state (no getFov method anymore).
			float fovDeg = this.mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.hudFov;
			float aspect = (float) this.mc.getWindow().getWidth() / Math.max(1.0F, (float) this.mc.getWindow().getHeight());
			float[] cloudColor = this.getCloudColor(partialTick);
			this.atmosphericClouds.render(this.drawPipeline, view, partialTick,
					cloudColor[0], cloudColor[1], cloudColor[2], fovDeg, aspect);
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

	// Plan item 3: spatial storm-fog coverage around the camera, and up to MAX_BOLTS live bolts
	// that light the fog ([x, y, z, strength, r, g, b, radius] each), nearest first. Every bolt is
	// logged once with the fog light it gets, so the S5 strikes at 200/1500/3000/8000 blocks are
	// proof data for "a far strike lights nothing".
	private final dev.nonamecrackers2.simpleclouds.client.renderer.v2.StormFogMap stormFogMap =
			new dev.nonamecrackers2.simpleclouds.client.renderer.v2.StormFogMap();
	private final float[] fogBolts = new float[dev.nonamecrackers2.simpleclouds.client.renderer.v2.StormFogMap.MAX_BOLTS * 8];
	private int fogBoltCount;
	private final java.util.Set<dev.nonamecrackers2.simpleclouds.client.renderer.lightning.LightningBolt> loggedFogBolts =
			java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
	private static boolean devFogFlashes = true;
	private static int devStormFogDebug;

	/** DevShot NOFLASH: storm fog without bolt light (A/B against the default). */
	public static void setDevFogFlashes(boolean on)
	{
		devFogFlashes = on;
	}

	/** DevShot FOGDEBUG: 1 = reconstructed scene distance (depth convention check), 2 = fog amount. */
	public static void setDevStormFogDebug(int mode)
	{
		devStormFogDebug = mode;
	}

	private void collectFogBolts(double camX, double camY, double camZ, float partialTick, float fogMax, boolean lightOn)
	{
		final float radius = dev.nonamecrackers2.simpleclouds.client.renderer.v2.StormFogMap.BOLT_RADIUS;
		java.util.List<float[]> near = new java.util.ArrayList<>();
		this.getWorldEffectsManager().forLightning(bolt ->
		{
			org.joml.Vector3f p = bolt.getPosition();
			double dx = p.x - camX, dy = p.y - camY, dz = p.z - camZ;
			float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
			boolean inRange = dist <= fogMax + radius;
			float strength = lightOn && inRange ? Mth.clamp(bolt.getFade(partialTick), 0.0F, 1.0F) : 0.0F;
			if (this.loggedFogBolts.add(bolt))
				LOGGER.info("[DEVSHOT-LIGHTNING] storm fog: bolt at {} blocks -> {}", Math.round(dist),
						!lightOn ? "no fog light (storm fog lightning flashes off or Hide Lightning Flashes on)"
								: inRange ? "local fog light within " + (int) radius + " blocks of the bolt"
								: "no fog light (beyond the " + (int) (fogMax + radius) + "-block fog range)");
			if (strength > 0.0F)
				near.add(new float[] { p.x, p.y, p.z, strength, dist });
		});
		near.sort((a, b) -> Float.compare(a[4], b[4]));
		this.fogBoltCount = Math.min(near.size(), dev.nonamecrackers2.simpleclouds.client.renderer.v2.StormFogMap.MAX_BOLTS);
		for (int i = 0; i < this.fogBoltCount; i++)
		{
			float[] b = near.get(i);
			int o = i * 8;
			this.fogBolts[o] = b[0];
			this.fogBolts[o + 1] = b[1];
			this.fogBolts[o + 2] = b[2];
			this.fogBolts[o + 3] = b[3];
			this.fogBolts[o + 4] = 0.75F;
			this.fogBolts[o + 5] = 0.8F;
			this.fogBolts[o + 6] = 1.0F;
			this.fogBolts[o + 7] = radius;
		}
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
		this.initialSyncWaitStartedTick = Long.MIN_VALUE;
		this.initialSyncTimeoutLogged = false;
		this.initialFieldRevealed = false;
		this.initialFieldWaitStartedTick = Long.MIN_VALUE;
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
		this.previousBatch.values().forEach(SimpleCloudsRenderer::closeChunk);
		this.previousBatch.clear();
		synchronized (this.completionLock)
		{
			this.workersStopped = true;
			ChunkResult result;
			while ((result = this.completedChunks.poll()) != null)
			{
				this.chunkBufferPool.release(result.opaque());
				this.chunkBufferPool.release(result.transparent());
			}
		}
		this.chunkJobQueue.clear();
		this.batchWaiting.clear();
		this.batchExpected.clear();
		this.batchDeltaSources.clear();
		this.batchWorldSources.clear();
		this.batchInProgress = false;
		this.pendingChunks.clear();
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
		if (this.gpuPostPool != null)
		{
			this.gpuPostPool.shutdownNow();
			this.gpuPostPool = null;
		}
		for (int i = 0; i < this.gpuSlots.length; i++)
		{
			if (this.gpuSlots[i] != null) this.gpuSlots[i].generator.close();
			this.gpuSlots[i] = null;
		}
		this.pendingGpuOpaque.values().forEach(GpuOpaqueCandidate::close);
		this.pendingGpuOpaque.clear();
		this.pendingGpuTransparent.values().forEach(GpuTransparentCandidate::close);
		this.pendingGpuTransparent.clear();
		if (this.gpuFaceDelta != null) { this.gpuFaceDelta.close(); this.gpuFaceDelta = null; }
		if (this.gpuTransparentDelta != null) { this.gpuTransparentDelta.close(); this.gpuTransparentDelta = null; }
		if (this.gpuTransparentExpansion != null) { this.gpuTransparentExpansion.close(); this.gpuTransparentExpansion = null; }
		if (this.gpuStormBits != null) { this.gpuStormBits.close(); this.gpuStormBits = null; }
		this.gpuWorldFailed = false;
		this.gpuOnlyCompleted = 0;
		// A1: free the per-chunk GPU buffers.
		if (!WORLD_COVERAGE) for (ChunkData d : this.chunkCaches.values()) closeChunk(d);
		else this.worldCoverage.close();
		this.chunkCaches.clear();
		this.retainedGridChunks.values().forEach(retained -> closeChunk(retained.value()));
		this.retainedGridChunks.clear();
		this.lastChunkGridKey = null;
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
		if (this.mc.level == null)
			return new float[] { 1.0F, 1.0F, 1.0F };
		// 26.3 moved vanilla cloud colour from ClientLevel.getCloudColor to
		// the extracted level render state (EnvironmentAttributes.CLOUD_COLOR).
		int vanilla = this.mc.gameRenderer.gameRenderState().levelRenderState.cloudColor;
		float factor = this.getWorldEffectsManager().getDarkenFactor(partialTick, 0.8F)
				+ this.getWorldEffectsManager().flashStrength(partialTick)
				* dev.nonamecrackers2.simpleclouds.common.cloud.SimpleCloudsConstants.LIGHTNING_FLASH_STRENGTH;
		return new float[] {
				Mth.clamp(((vanilla >> 16) & 0xFF) / 255.0F * factor, 0.0F, 1.0F),
				Mth.clamp(((vanilla >> 8) & 0xFF) / 255.0F * factor, 0.0F, 1.0F),
				Mth.clamp((vanilla & 0xFF) / 255.0F * factor, 0.0F, 1.0F)
		};
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
		if (this.mc.isPaused()) partialTick = this.lastUnpausedPartialTick;
		else this.lastUnpausedPartialTick = partialTick;
		// One reset per rendered frame of the pipeline's private transform ring (see
		// CloudsDrawPipeline.beginFrame): every cloud, shadow, lightning, storm and transition
		// draw of this frame writes its slice after it.
		if (this.drawPipeline != null)
			this.drawPipeline.beginFrame();
		// The original tints every cloud pass with vanilla's time/weather cloud
		// color, then applies local storm darkening and lightning brightening.
		if (this.drawPipeline != null)
		{
			float[] cloudColor = this.getCloudColor(partialTick);
			this.drawPipeline.setCloudColor(cloudColor[0], cloudColor[1], cloudColor[2]);
		}
		// 26.2 3D previewer: while the previewer screen is open, draw the preview
		// box into the main frame (snippet pipeline, orbit camera, no depth test)
		// instead of the normal cloud pass.
		if (this.mc.gui.screen() instanceof CloudPreviewerScreen previewScreen)
		{
			this.drawPreviewInWorld(previewScreen, camX, camY, camZ);
			return;
		}
		this.generateAndDrawClouds(camX, camY, camZ, partialTick, projMat);
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
				if (s3d instanceof dev.nonamecrackers2.simpleclouds.client.gui.CloudPreviewerScreen preview)
					this.previewPipeline.generateMesh(preview.selectedCloudType());
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

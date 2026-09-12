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

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.PoseStack;

import dev.nonamecrackers2.simpleclouds.SimpleCloudsMod;
import dev.nonamecrackers2.simpleclouds.api.common.cloud.CloudMode;
import dev.nonamecrackers2.simpleclouds.api.common.cloud.weather.WeatherType;
import dev.nonamecrackers2.simpleclouds.client.compat.SimpleCloudsCompatHelper;
import dev.nonamecrackers2.simpleclouds.client.framebuffer.ShadowMapBuffer;
import dev.nonamecrackers2.simpleclouds.client.framebuffer.WeightedBlendingTarget;
import dev.nonamecrackers2.simpleclouds.client.cloud.ClientSideCloudTypeManager;
import dev.nonamecrackers2.simpleclouds.client.gui.CloudPreviewerScreen;
import dev.nonamecrackers2.simpleclouds.client.mesh.RendererInitializeResult;
import dev.nonamecrackers2.simpleclouds.client.mesh.generator.CloudMeshGenerator;
import dev.nonamecrackers2.simpleclouds.common.cloud.CloudType;
import dev.nonamecrackers2.simpleclouds.common.noise.AbstractNoiseSettings;
import dev.nonamecrackers2.simpleclouds.common.noise.NoiseSettings;
import dev.nonamecrackers2.simpleclouds.common.noise.StaticLayeredNoise;
import dev.nonamecrackers2.simpleclouds.common.noise.StaticNoiseSettings;
import dev.nonamecrackers2.simpleclouds.client.renderer.pipeline.CloudsRenderPipeline;
import dev.nonamecrackers2.simpleclouds.client.renderer.settings.CloudsRendererSettings;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.CloudsDrawPipeline;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.CpuCloudGenerator;
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
			// Owned by the band worker thread (never touched from the render thread).
			this.cpuGenerator = new CpuCloudGenerator(List.of());
			this.startBandWorker();
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

	// Full-region generation cache: per-band instance data keyed by band origin.
	// A band regenerates only when its region signature or the data-driven group set
	// changes; at most one band per frame (see generateAndDrawClouds).
	private final java.util.Map<BandCoord, BandData> bandCaches = new java.util.HashMap<>();
	private int cacheGroupsHash = Integer.MIN_VALUE;
	private String lastBandGridKey;
	private boolean instanceBuffersDirty = true;

	/** Generated instance data for one 32x32-unit (256x256 block) band. */
	private static final class BandData
	{
		long regionSig;
		int groupsHash;
		java.nio.ByteBuffer opaque;
		int opaqueCount;
		java.nio.ByteBuffer transparent;
		int transparentCount;
		float stormCoverage;
	}

	/** Band identity: XZ origin plus the quantized volume base (clouds follow the camera in Y). */
	private record BandCoord(int x0, int z0, int baseU)
	{
	}

	// Band geometry (cloud units): one 32x32-unit band; Y = 256 units (2048 blocks),
	// the original's VERTICAL_CHUNK_SPAN * CHUNK_SIZE, camera-anchored (see below).
	private static final int BAND_CELL = 32;
	private static final int BAND_Y1 = 256;
	private static final int BAND_Y_STEP = 16; // baseU quantization (128-block steps)
	private static final float BAND_SCALE = 8.0F;

	// Off-thread band generation: a full band (32x64x32 cells x every noise layer)
	// takes ~150 ms, so the render thread must not pay for it. A dedicated worker
	// thread owns `cpuGenerator`, pulls band jobs and publishes results; the render
	// thread only enqueues stale bands and picks up finished ones (see
	// generateAndDrawClouds). All job inputs are immutable records, so crossing the
	// thread boundary is safe.
	private final java.util.concurrent.LinkedBlockingQueue<BandJob> bandQueue = new java.util.concurrent.LinkedBlockingQueue<>();
	private final java.util.concurrent.ConcurrentLinkedQueue<BandResult> completedBands = new java.util.concurrent.ConcurrentLinkedQueue<>();
	private final java.util.Set<BandCoord> pendingBands = java.util.concurrent.ConcurrentHashMap.newKeySet();
	@Nullable
	private java.util.concurrent.ExecutorService bandWorker;

	/** One off-thread band generation request (immutable inputs). */
	private record BandJob(BandCoord coord, int x0, int z0, List<CpuCloudGenerator.CloudLayerGroup> groups,
			List<CpuCloudGenerator.RegionMask> regions, long regionSig, int groupsHash, int camGridY)
	{
	}

	/** A finished band generation. */
	private record BandResult(BandCoord coord, BandData data)
	{
	}

	private void startBandWorker()
	{
		if (this.bandWorker != null)
			return;
		this.bandWorker = java.util.concurrent.Executors.newSingleThreadExecutor(r ->
		{
			Thread t = new Thread(r, "simpleclouds-bandgen");
			t.setDaemon(true);
			return t;
		});
		this.bandWorker.execute(this::bandWorkerLoop);
	}

	/** The dedicated generation thread: pulls band jobs, publishes the results. */
	private void bandWorkerLoop()
	{
		BandJob job;
		try
		{
			while ((job = this.bandQueue.take()) != null)
			{
				CpuCloudGenerator generator = this.cpuGenerator;
				if (generator == null)
					continue;
				try
				{
					generator.setGroups(job.groups());
					generator.setRegions(job.regions());
					float[] opaqueCount = new float[1];
					float[] transparentCount = new float[1];
					float[] stormCoverage = new float[1];
					long t0 = System.nanoTime();
					java.nio.ByteBuffer[] data = generator.generate(
							job.x0(), job.coord().baseU(), job.z0(), job.x0() + BAND_CELL,
							job.coord().baseU() + BAND_Y1, job.z0() + BAND_CELL,
							BAND_SCALE, 0.0F, 0.0F, 0.0F, 0.0F, opaqueCount, transparentCount, job.camGridY(), stormCoverage);
					BandData d = new BandData();
					d.regionSig = job.regionSig();
					d.groupsHash = job.groupsHash();
					d.opaque = data[0];
					d.opaqueCount = (int) opaqueCount[0];
					d.transparent = data[1];
					d.transparentCount = (int) transparentCount[0];
					d.stormCoverage = stormCoverage[0];
					this.completedBands.add(new BandResult(job.coord(), d));
					LOGGER.info("Simple Clouds clouds: band {}x{} (baseY {}) generated off-thread, {} formations, {} opaque / {} transparent instances (CPU {} us)",
							job.x0(), job.z0(), job.coord().baseU(), job.regions().size(), d.opaqueCount, d.transparentCount, (System.nanoTime() - t0) / 1000L);

				}
				catch (Throwable t)
				{
					LOGGER.error("Simple Clouds clouds: off-thread band generation failed", t);
				}
				finally
				{
					// Release the pending slot even on failure so the band can be
					// re-requested next frame.
					this.pendingBands.remove(job.coord());
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

		// Full-region rendering. The cloud field is world-fixed (CLOUD_SCALE = 8 world
		// blocks per cloud unit; region positions/radii and the noise coordinates live
		// in that space), but generation is band-local: the region is a grid of
		// 32x32-unit (256x256 block) bands around the camera covering the render
		// distance (2x2 up to RD 16, 3x3 beyond), fixed in Y at 0..64 units
		// (0..512 blocks) covering every type's noise heights.
		float scale = 8.0F;
		final int cell = 32;
		final int cellBlocks = (int) (cell * scale);
		int camGridY = Mth.floor(camY / scale);
		// Parity with 1.20.1: the cloud volume is anchored cloudHeight (config,
		// default 128 blocks) BELOW the camera, so it follows the player's altitude.
		// Quantized to BAND_Y_STEP units (128-block steps) so the per-band cache stays
		// valid while the player moves vertically within a step.
		int cloudHeight = 128;
		var cloudManager = CloudManager.get(Minecraft.getInstance().level);
		if (cloudManager != null)
			cloudHeight = cloudManager.getCloudHeight();
		int baseU = Mth.floor((camY - (double) cloudHeight) / scale / (double) BAND_Y_STEP) * BAND_Y_STEP;
		int renderDistance = Mth.clamp(this.mc.options.renderDistance().get(), 2, 64);
		int bands = Mth.clamp(Mth.ceil(renderDistance * 16.0 / (double) cellBlocks), 2, 3);
		int cx = Mth.floor(camX / cellBlocks);
		int cz = Mth.floor(camZ / cellBlocks);
		String gridKey = cx + "," + cz + "," + bands + "," + baseU;

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
		boolean bandSetChanged = gridKey != this.lastBandGridKey;
		this.lastBandGridKey = gridKey;

		// Band plan: every grid cell, ordered by distance to the camera so the
		// regeneration budget fills the view from the center out.
		java.util.List<long[]> cells = new java.util.ArrayList<>(bands * bands);
		double camCellX = camX / (double) cellBlocks, camCellZ = camZ / (double) cellBlocks;
		for (int bx = 0; bx < bands; bx++)
		{
			int offX = bx - (bands - 1) / 2;
			for (int bz = 0; bz < bands; bz++)
			{
				int offZ = bz - (bands - 1) / 2;
				double dx = (cx + offX) + 0.5 - camCellX;
				double dz = (cz + offZ) + 0.5 - camCellZ;
				cells.add(new long[] { cx + offX, cz + offZ, (long) ((dx * dx + dz * dz) * 1000.0) });
			}
		}
		cells.sort(java.util.Comparator.comparingLong(c -> c[2]));

		// Pick up finished bands (generated off-thread — no render hitch).
		// Last-writer-wins: a completion is at most one generation cycle
		// (~0.1-0.2 s) behind the current region state, far below the visible drift
		// of the formations, so stamp it with the CURRENT signature. (Comparing it
		// against the signature it was requested with live-locked the cache: moving
		// formations cross the signature quantization boundaries constantly, so every
		// completion was discarded and re-enqueued until they slowed down.)
		BandResult result;
		while ((result = this.completedBands.poll()) != null)
		{
			BandData d = result.data();
			d.regionSig = regionSig;
			d.groupsHash = groupsHash;
			this.bandCaches.put(result.coord(), d);
			this.instanceBuffersDirty = true;
		}

		// Enqueue every stale band (camera-out order; the worker processes them in
		// that order). pendingBands makes enqueuing idempotent across frames.
		for (long[] c : cells)
		{
			int x0 = (int) c[0] * cell;
			int z0 = (int) c[1] * cell;
			BandCoord key = new BandCoord(x0, z0, baseU);
			BandData d = this.bandCaches.get(key);
			boolean stale = d == null || d.regionSig != regionSig || d.groupsHash != groupsHash || groupsChanged;
			if (stale && this.pendingBands.add(key))
				this.bandQueue.add(new BandJob(key, x0, z0, groups, regions, regionSig, groupsHash, camGridY));
		}

		float maxStorm = 0.0F;
		int totalOpaque = 0, totalTransp = 0;
		for (long[] c : cells)
		{
			BandData d = this.bandCaches.get(new BandCoord((int) c[0] * cell, (int) c[1] * cell, baseU));
			if (d != null)
			{
				totalOpaque += d.opaqueCount;
				totalTransp += d.transparentCount;
				if (d.stormCoverage > maxStorm)
					maxStorm = d.stormCoverage;
			}
		}
		// Evict bands that left the grid (keeps the map bounded to the grid size).
		if (bandSetChanged)
		{
			java.util.Set<BandCoord> keep = new java.util.HashSet<>(cells.size());
			for (long[] c : cells)
				keep.add(new BandCoord((int) c[0] * cell, (int) c[1] * cell, baseU));
			this.bandCaches.keySet().retainAll(keep);
			this.instanceBuffersDirty = true;
		}
		this.cacheStormCoverage = maxStorm;

		// Rebuild the combined instance buffers only when something actually changed
		// (setInstances uploads a fresh GPU buffer, so it must not run every frame).
		if (bandSetChanged || this.instanceBuffersDirty)
		{
			this.instanceBuffersDirty = false;
			java.nio.ByteBuffer opaqueOut = null, transpOut = null;
			if (totalOpaque > 0)
			{
				opaqueOut = java.nio.ByteBuffer.allocateDirect(totalOpaque * 24).order(java.nio.ByteOrder.nativeOrder());
				for (long[] c : cells)
				{
					BandData d = this.bandCaches.get(new BandCoord((int) c[0] * cell, (int) c[1] * cell, baseU));
					if (d != null && d.opaqueCount > 0)
					{
						d.opaque.rewind();
						opaqueOut.put(d.opaque);
					}
				}
				opaqueOut.flip();
			}
			// Config gate (1.20.1's renderCloudsTransparency only ran when transparency
			// was enabled in the client config).
			boolean transparencyEnabled = SimpleCloudsConfig.CLIENT.transparency.get();
			if (transparencyEnabled && totalTransp > 0)
			{
				// Transparent instances carry an extra alpha float (28 bytes, not 24).
				transpOut = java.nio.ByteBuffer.allocateDirect(totalTransp * 28).order(java.nio.ByteOrder.nativeOrder());
				for (long[] c : cells)
				{
					BandData d = this.bandCaches.get(new BandCoord((int) c[0] * cell, (int) c[1] * cell, baseU));
					if (d != null && d.transparentCount > 0)
					{
						d.transparent.rewind();
						transpOut.put(d.transparent);
					}
				}
				transpOut.flip();
			}
			this.drawPipeline.setInstances(opaqueOut, totalOpaque);
			this.drawPipeline.setTransparencyInstances(transpOut, transpOut == null ? 0 : totalTransp);
			LOGGER.info("Simple Clouds clouds: {} bands -> {} opaque / {} transparent instances (cam {}x{}x{}, cell {}x{}, grid {}x{})",
					this.bandCaches.size(), totalOpaque, transpOut == null ? 0 : totalTransp,
					camX, camY, camZ, cx, cz, bands, bands);
		}

		// SPIKE (SPIKE-GPU.md): original cube_mesh.comp via raw OpenGL (OFF — see
		// SPIKE-GPU-RESULT.md). Wired to the camera band; re-enable SPIKE_GPU once
		// the driver honors the core GL contract.
		if (SPIKE_GPU)
		{
			int x0 = (int) cells.get(0)[0] * cell;
			int z0 = (int) cells.get(0)[1] * cell;
			// GlDevice is package-private in 26.2 behind the GpuDevice facade; detect
			// the OpenGL backend through the facade's private 'backend' field.
			if (!this.spikeClassLogged)
			{
				this.spikeClassLogged = true;
				String backendName = GpuCloudGeneration.backendClassName();
				LOGGER.info("Spike: device backend class = {}", backendName);
			}
			if (this.spike == null && GpuCloudGeneration.isOpenGLBackend())
				this.spike = new GpuCloudGeneration();
			if (this.spike != null && !this.spikeReady && !this.spikeInitFailed)
			{
				this.spikeReady = this.spike.init();
				if (!this.spikeReady)
				{
					this.spikeInitFailed = true;
					GpuCloudGeneration.LOGGER.warn("Spike: GPU generation unavailable; keeping the CPU path");
				}
			}
			if (this.spikeReady && !groups.isEmpty())
			{
				int bandCenterX = x0 + cell / 2, bandCenterZ = z0 + cell / 2;
				int gpuCount = this.spike.generate(x0, baseU, z0, x0 + cell, baseU + BAND_Y1, z0 + cell,
						bandCenterX, 32.0F, bandCenterZ, 8.0F, 16.0F,
						groups.get(0).layers(), groups.get(0).transparencyFade());
				// Only trust the GPU result when it actually produced instances; an
				// empty (or failed) generation must never hide the CPU clouds.
				if (gpuCount > 0 && this.spike.instanceData() != null)
				{
					this.drawPipeline.setInstances(this.spike.instanceData(), gpuCount);
					LOGGER.info("Spike timing: CPU {} us vs GPU {} us (dispatch+readback), band {}x{}x{} units, GPU {} instances",
							this.lastCpuGenerateNanos / 1000L, this.spike.lastGenerateNanos() / 1000L,
							cell, BAND_Y1, cell, gpuCount);
				}
			}
		}

		this.drawPipeline.draw(view);
		this.drawPipeline.drawTransparency(view);

		// World effects: custom rain (1.20.1 PrecipitationQuads; slice without
		// wind tilt / snow) into the scene, before the overlays.
		if (SimpleCloudsConfig.CLIENT.renderCustomRain.get())
			this.getWorldEffectsManager().renderRain(view, partialTick, camX, camY, camZ);

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
		this.drawPipeline.renderCloudShadowMap(camX, camY, camZ);
		this.drawPipeline.drawTerrainShadows(view, camX, camY, camZ);

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
		if (this.bandWorker != null)
		{
			this.bandWorker.shutdownNow();
			this.bandWorker = null;
		}
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

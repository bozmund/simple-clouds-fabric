package dev.nonamecrackers2.simpleclouds.client.renderer;

import dev.nonamecrackers2.simpleclouds.client.renderer.v2.CloudGenerationInputs;

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
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.CloudVertexFormat;
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
 * Original GPU cloud generation adapted to the 26.3 RenderPipeline backend.
 * Original storm-volume/offscreen and remaining full-port parity are still in progress.
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
	@Nullable private dev.nonamecrackers2.simpleclouds.client.renderer.v2.OriginalCloudDrawBuffers originalDrawBuffers;
	@Nullable private dev.nonamecrackers2.simpleclouds.client.renderer.v2.OriginalCloudFrameTimings frameTimings;
	@Nullable private CloudsRenderPipeline renderPipelineThisPass;
	private float fogStart;
	private float fogEnd;
	@Nullable private Frustum cullFrustum;
	private boolean needsReload;
	@Nullable private RendererInitializeResult initialInitializationResult;

	// 26.2 vertical-slice rendering.
	@Nullable private CloudsDrawPipeline drawPipeline;
	@Nullable private AtmosphericCloudsRenderHandler atmosphericClouds;
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
		var serverConfig = dev.nonamecrackers2.simpleclouds.client.config.ClientServerConfig.get();
		if (dev.nonamecrackers2.simpleclouds.client.world.ClientCloudManager.isAvailableServerSide()
				&& serverConfig != null)
			return serverConfig.allowsDimension(level.dimension().identifier().toString());
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

	public static List<CloudGenerationInputs.CloudLayerGroup> dataDrivenGroups()
	{
		return dataDrivenGroups(selectedCloudTypes());
	}

	/** Explicit source for previews, independent of the world's current cloud mode. */
	public static List<CloudGenerationInputs.CloudLayerGroup> dataDrivenGroups(CloudType[] types)
	{
		List<CloudGenerationInputs.CloudLayerGroup> out = new java.util.ArrayList<>();
		for (CloudType type : types)
		{
			if (!hasRenderableLayers(type))
				continue;
			NoiseSettings settings = type.noiseConfig();
			List<CloudGenerationInputs.NoiseLayer> layers = new java.util.ArrayList<>();
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
				out.add(new CloudGenerationInputs.CloudLayerGroup(layers, type.transparencyFade(),
						type.weatherType() == WeatherType.THUNDERSTORM,
						// Step 8: per-type storm shading (dark undersides).
						type.storminess(), type.stormStart(), type.stormFadeDistance()));
		}
		return out;
	}

	private static CloudGenerationInputs.NoiseLayer toCpuLayer(AbstractNoiseSettings<?> layer)
	{
		return new CloudGenerationInputs.NoiseLayer(
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

	private List<CloudGenerationInputs.RegionMask> buildRegionMasks(java.util.Map<net.minecraft.resources.Identifier, Integer> typeToGroup, float partialTick)
	{
		if (this.cloudManager == null)
			return List.of();
		if (this.cloudManager.getCloudMode() == dev.nonamecrackers2.simpleclouds.api.common.cloud.CloudMode.SINGLE)
			return List.of(); // Infinite selected type; ignore leftover multi-region cells.
		var clouds = this.cloudManager.getClouds();
		if (clouds.isEmpty())
			return List.of();
		java.util.List<CloudGenerationInputs.RegionMask> out = new java.util.ArrayList<>(clouds.size());
		for (dev.nonamecrackers2.simpleclouds.common.cloud.region.CloudRegion region : clouds)
		{
			Integer gi = typeToGroup.get(region.getCloudTypeId());
			if (gi == null)
				continue; // type unknown to the client (not synced / data mismatch)
			org.joml.Matrix2f t = region.createTransform(partialTick);
			out.add(new CloudGenerationInputs.RegionMask(
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
	private static final int PRIMARY_CHUNK=SimpleCloudsConstants.CHUNK_SIZE;
	private static final float CLOUD_SCALE_F=SimpleCloudsConstants.CLOUD_SCALE;
	private final java.util.List<CloudsDrawPipeline.InstanceSource> shadowSources=new java.util.ArrayList<>();
	@Nullable private LevelOfDetailConfig lodConfig;
	private boolean loggedFog;
	private float lastUnpausedPartialTick;
	private long initialSyncWaitStartedTick=Long.MIN_VALUE;
	private boolean initialSyncTimeoutLogged;

	public float getChunkFillFraction() {
		return this.meshGenerator!=null && this.meshGenerator.getCompletedGenerationCycles()>0?1f:0f;
	}
	public long getPublishedBatchCount() {
		return this.meshGenerator!=null?this.meshGenerator.getCompletedGenerationCycles():0;
	}
	public boolean isCaptureFieldSettled() {return this.getChunkFillFraction()>=.97f;}
	public String generationDiagnostic() {
		return this.meshGenerator==null?"backend=ORIGINAL_GPU initializing=true":
			"backend=ORIGINAL_GPU cycles="+this.meshGenerator.getCompletedGenerationCycles()
			+" intervalFrames="+this.meshGenerator.getMeshGenInterval()
			+" status="+this.meshGenerator.getMeshGenStatus()+" cpuFallback=false";
	}
	public String meshDiagnostic() {
		return this.meshGenerator==null?"faces=0 initializing=true":
			"faces="+this.meshGenerator.getOpaqueBufferBytesUsed()/CloudMeshGenerator.BYTES_PER_SIDE_INFO
			+" transparentCubes="+this.meshGenerator.getTransparentBufferBytesUsed()/CloudMeshGenerator.BYTES_PER_CUBE_INFO
			+" originalChunkFadeTicks="+(int)(1f/CHUNK_FADE_IN_ALPHA_PER_TICK);
	}
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
	 * Defer native precipitation only when the active frame uses screen-space terrain fog.
	 * Clouds already draw after sky. Weather is copied while available, then prepared and
	 * rendered once after the terrain post-fog, unless custom precipitation owns that frame.
	 */
	public static boolean redrawsVanillaWeather()
	{
		// Native weather must not be included in the subsequent terrain-fog color copy.
		// Custom precipitation already draws after fog, so never duplicate it here.
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null && canRenderInDimension(mc.level)
				&& !SimpleCloudsCompatHelper.renderCustomRain()
				&& SimpleCloudsConfig.CLIENT.fogMode.get() == dev.nonamecrackers2.simpleclouds.client.world.FogRenderMode.SCREEN_SPACE
				&& getOptionalInstance().map(renderer -> renderer.worldCloudPassReady).orElse(false);
	}

	/** Vanilla's weather columns for this frame, copied before vanilla clears them. */
	private static final net.minecraft.client.renderer.state.level.WeatherRenderState CAPTURED_WEATHER =
			new net.minecraft.client.renderer.state.level.WeatherRenderState();
	private static boolean capturedWeatherThisFrame;
	private static boolean renderingDeferredWeather;
	public static boolean isRenderingDeferredWeather() { return renderingDeferredWeather; }

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
		// Bypass ONLY our interception during this exact replay, including failure cleanup.
		renderingDeferredWeather = true;
		try {
			this.drawPipeline.drawVanillaWeather(mc.levelRenderer.weatherEffectRenderer(), CAPTURED_WEATHER,
					camera.position(), view);
		} finally {
			renderingDeferredWeather = false;
			CAPTURED_WEATHER.reset();
		}
	}




	private void closeOriginalGenerator() {
		if (this.frameTimings != null) { this.frameTimings.close(); this.frameTimings=null; }
		if (this.originalDrawBuffers != null) { this.originalDrawBuffers.close(); this.originalDrawBuffers=null; }
		if (this.meshGenerator != null) { this.meshGenerator.close(); this.meshGenerator=null; }
	}

	private static int calculateOriginalMeshGenInterval() {
		int fps=Minecraft.getInstance().getFps();
		return switch(SimpleCloudsConfig.CLIENT.generationInterval.get()) {
			case STATIC -> SimpleCloudsConfig.CLIENT.framesToGenerateMesh.get();
			case DYNAMIC -> Math.max(Mth.ceil((130f-fps)/30f)+5,1);
			case TARGET_FPS -> Math.max(Mth.ceil((float)fps/SimpleCloudsConfig.CLIENT.targetMeshGenFps.get()),1);
		};
	}

	private void prepareOriginalGenerator(CloudManager<?> manager) {
		if (!GpuCloudGeneration.isOpenGLBackend())
			throw new IllegalStateException("Original GPU generation requires the OpenGL backend; no CPU fallback");
		if (this.settings.checkAndOrBeginInitialization(this.meshGenerator)) {
			this.closeOriginalGenerator();
			CloudMode mode=this.settings.getCurrentCloudMode();
			// Keep faces that point away from the camera too (Jan 2026-10-04): with the original
			// camera-facing cull, the walls around a camera inside a cloud were never generated and
			// the cloud was see-through from inside.
			var builder=CloudMeshGenerator.builder().fadeNearOrigin(mode==CloudMode.AMBIENT).testFacesFacingAway(!devCullAwayFaces)
				.shadedClouds(this.settings.shadedClouds()).useTransparency(this.settings.useTransparency())
				.fixedMeshDataSectionSize(this.settings.useFixedMeshDataSectionSize())
				.lodConfig(this.settings.getCurrentLod().getConfig())
				.meshGenInterval(SimpleCloudsRenderer::calculateOriginalMeshGenInterval);
			if(mode==CloudMode.DEFAULT || mode==CloudMode.AMBIENT) {
				if(mode==CloudMode.AMBIENT) builder.fadeStart(SimpleCloudsConstants.AMBIENT_MODE_FADE_START).fadeEnd(SimpleCloudsConstants.AMBIENT_MODE_FADE_END);
				this.meshGenerator=builder.createMultiRegion();
			} else if(mode==CloudMode.SINGLE) {
				this.meshGenerator=builder.fadeStart(SimpleCloudsConfig.CLIENT.singleModeFadeStartPercentage.get()/100f)
					.fadeEnd(SimpleCloudsConfig.CLIENT.singleModeFadeEndPercentage.get()/100f).createSingleRegion(SimpleCloudsConstants.EMPTY);
			} else throw new IllegalArgumentException("Unknown cloud mode: "+mode);
			this.updateOriginalGeneratorClouds(manager);
			var result=this.meshGenerator.init(this.mc.getResourceManager());
			if(!result.getErrors().isEmpty()) {
				for(var error:result.getErrors()) LOGGER.error("Original GPU generator initialization: {}",error.title(),error.error());
				this.closeOriginalGenerator();
				throw new IllegalStateException("Original GPU generator initialization failed; no CPU fallback");
			}
			try {this.originalDrawBuffers=new dev.nonamecrackers2.simpleclouds.client.renderer.v2.OriginalCloudDrawBuffers(this.mc.getResourceManager());}
			catch(java.io.IOException failure) {this.closeOriginalGenerator();throw new IllegalStateException("Original draw adapter initialization failed",failure);}
			LOGGER.info("[ORIGINAL-GPU] initialized mode={} chunks={} originalCadence=true cpuFallback=false",mode,this.meshGenerator.getTotalMeshChunks());
		} else this.updateOriginalGeneratorClouds(manager);
	}

	private void updateOriginalGeneratorClouds(CloudManager<?> manager) {
		if(this.meshGenerator instanceof dev.nonamecrackers2.simpleclouds.client.mesh.generator.MultiRegionCloudMeshGenerator multi)
			multi.setCloudGetter(manager!=null?manager:dev.nonamecrackers2.simpleclouds.common.cloud.region.CloudGetter.EMPTY);
		else if(this.meshGenerator instanceof dev.nonamecrackers2.simpleclouds.client.mesh.generator.SingleRegionCloudMeshGenerator single) {
			CloudType type=this.settings.getSingleModeCloudType();
			if(!ClientCloudManager.isAvailableServerSide() && !ClientSideCloudTypeManager.isValidClientSideSingleModeCloudType(type)) type=SimpleCloudsConstants.EMPTY;
			single.setCloudType(type!=null?type:SimpleCloudsConstants.EMPTY);
		}
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
		if (this.drawPipeline == null)
		{
			// Retry (throttled to every 64th failed frame) instead of staying dark.
			if ((this.pipelineRetryFrame++ & 63) == 0)
				this.ensurePipeline();
			if (this.drawPipeline == null)
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

		int cloudHeight = driftManager != null ? driftManager.getCloudHeight() : 128;
		this.prepareOriginalGenerator(driftManager);
		if (this.meshGenerator == null || this.originalDrawBuffers == null) return;
		this.lodConfig = this.meshGenerator.getLodConfig();
		double originX=camX/CLOUD_SCALE_F, originY=(camY-cloudHeight)/CLOUD_SCALE_F, originZ=camZ/CLOUD_SCALE_F;
		if (this.cullFrustum != null) this.cullFrustum.prepare(originX,originY,originZ);
		this.meshGenerator.setScroll(scrollX,scrollY,scrollZ);
		float radius=Math.max(2867f,this.meshGenerator.getCloudAreaMaxRadius()*CLOUD_SCALE_F);
		this.meshGenerator.setCullDistance(radius/CLOUD_SCALE_F);
		if (this.frameTimings == null)
			this.frameTimings=dev.nonamecrackers2.simpleclouds.client.renderer.v2.OriginalCloudFrameTimings.createIfEnabled();
		if (this.frameTimings != null) this.frameTimings.begin();
		if (!this.mc.isPaused() && SimpleCloudsConfig.CLIENT.generateMesh.get() && SimpleCloudsCompatHelper.isPrimaryPass())
			this.meshGenerator.genTick(originX,originY,originZ,this.cullFrustum,partialTick);
		if (this.frameTimings != null) this.frameTimings.mark(1);
		// No substitute CPU worker, fallback, whole-field barrier or per-face dissolve.
		// Original chunk alpha remains driven by worldTick, not wall-clock time.
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

		this.shadowSources.clear();
		if (this.frameTimings != null) this.frameTimings.mark(2);
		if (this.cloudsBeforeTranslucent || SimpleCloudsConfig.CLIENT.fogMode.get() == dev.nonamecrackers2.simpleclouds.client.world.FogRenderMode.SCREEN_SPACE)
			this.drawPipeline.capturePreCloudDepth();
		if (SimpleCloudsConfig.CLIENT.renderClouds.get() && SimpleCloudsCompatHelper.isPrimaryPass()) {
			this.meshGenerator.forRenderableMeshChunks(this.cullFrustum,
				dev.nonamecrackers2.simpleclouds.client.mesh.chunk.MeshChunk::getOpaqueBuffers,(chunk,buffers)->{
					var source=this.originalDrawBuffers.get(buffers,false,cloudHeight);
					if (!("1".equals(System.getenv("SIMPLECLOUDS_DEV"))
							&& "1".equals(System.getenv("SIMPLECLOUDS_DIAGNOSTIC_SKIP_OPAQUE"))))
						this.drawPipeline.drawClouds(view,source.buffer(),source.count(),chunk.getAlpha(partialTick),0,0,0);
					this.shadowSources.add(source);
				},true);
			if (this.frameTimings != null) this.frameTimings.mark(2);
			if (this.meshGenerator.transparencyEnabled() && !devSkipTransparency)
			{
				this.drawPipeline.beginTransparency();
				try {
				this.meshGenerator.forRenderableMeshChunks(this.cullFrustum,
					chunk->chunk.getTransparentBuffers().orElseThrow(),(chunk,buffers)->{
						var source=this.originalDrawBuffers.get(buffers,true,cloudHeight);
						this.drawPipeline.drawTransparencyClouds(view,source.buffer(),source.count(),chunk.getAlpha(partialTick),0,0,0);
					});
				} finally {
					this.drawPipeline.endTransparency();
				}
			}
		}
		// Storm volume comes from original GPU shadow geometry, not CPU columns.
		if (this.frameTimings != null) this.frameTimings.mark(3);
		this.cacheStormCoverage=0;
		if (driftManager != null && stormFogEnabled && overlaysEnabled && SimpleCloudsConfig.CLIENT.renderStormFog.get())
		{
			var wind = driftManager.calculateWindDirection();
			float span = this.meshGenerator.getLodConfig().getEffectiveChunkSpan()
					* SimpleCloudsConstants.CHUNK_SIZE * SimpleCloudsConstants.CLOUD_SCALE;
			this.drawPipeline.renderOriginalStormShadow(camX, camZ, (float)cloudHeight, span,
					SimpleCloudsConfig.CLIENT.stormFogAngle.get().floatValue(), wind.x, wind.y, this.shadowSources);
		}
		// Original storm fog: 200-step quadratic raymarch through GPU depth/brightness.
		if (this.frameTimings != null) this.frameTimings.mark(4);
		if (stormFogEnabled && overlaysEnabled && SimpleCloudsConfig.CLIENT.renderStormFog.get())
		{
			if (this.cloudsBeforeTranslucent)
			{
				// Composited straight into the main colour: drawn now, the translucent water
				// that follows would cover it. Run it at the TAIL (renderAfterLevel) instead.
				this.pendingStormFogView = new Matrix4f(view);
				this.pendingStormFogProjection = new Matrix4f(activeProjection);
			}
			else
				this.drawStormFogNow(view, activeProjection, camX, camY, camZ, partialTick);
		}

		// Sky flash (storm plan step 1): the vanilla 26.2 sky flash is dead (nothing
		// consumes ClientLevel.getSkyFlashTime), so the port draws its own short
		// full-screen white brightening on the same gated strength — visible only
		// while a rendered bolt is within 2000 blocks and bright, never for far
		// strikes, and never with "Hide Sky Flashes" on.
		// No full-screen sky flash: the original brightens the CLOUDS and nothing else (see
		// CloudsDrawPipeline.setFlashBoost). Flashing every sky pixel lit up a clear sky when the
		// only storm was over a kilometre away.

		if (this.frameTimings != null) this.frameTimings.mark(5);
		if (this.cloudsBeforeTranslucent || SimpleCloudsConfig.CLIENT.fogMode.get() == dev.nonamecrackers2.simpleclouds.client.world.FogRenderMode.SCREEN_SPACE)
			this.drawPipeline.captureCloudDepth();
		this.worldCloudPassReady = true;
		if (this.frameTimings != null) this.frameTimings.end();
	}

	private void drawStormFogNow(Matrix4f view, Matrix4f projection, double camX, double camY, double camZ, float partialTick)
	{
		boolean boltLight = devFogFlashes && SimpleCloudsConfig.CLIENT.stormFogLightningFlashes.get()
				&& !this.mc.options.hideLightningFlash().get();
		this.collectFogBolts(partialTick, boltLight);
		this.drawPipeline.drawStormFog(view, projection, camX, camY, camZ, this.getFogEnd(),
				this.getCloudColor(partialTick), this.fogBolts, this.fogBoltCount, devStormFogDebug);
	}

	/** Set while the DH cloud stage runs before the translucent stage (storm fog waits for the TAIL). */
	private boolean cloudsBeforeTranslucent;
	private Matrix4f pendingStormFogView, pendingStormFogProjection;

	private boolean worldCloudPassReady;
	private boolean worldCloudStageStarted;
	// DH: the sky stage draws only the atmospheric layer; clouds follow after DH's LODs.
	private boolean deferCloudsForDh;
	private boolean dhSkyStageDone;
	private boolean dhDepthMerged;
	private int worldCloudStageExecutions;
	public int getCompletedCloudStagesThisFrame() { return this.worldCloudStageExecutions; }
	private boolean worldCloudRepeatLogged;
	private boolean pipelinePrepared;
	private boolean shaderSelectionLogged, shaderStageLogged;
	public void verifyRepeatedSkyStage() {
		if ("1".equals(System.getenv("SIMPLECLOUDS_DEV")) && !this.worldCloudRepeatLogged
				&& this.worldCloudStageExecutions == 1 && this.worldCloudPassReady) {
			this.worldCloudRepeatLogged = true;
			LOGGER.info("[SKY-API-REPLAY] two API calls, completedCloudStages={}",this.worldCloudStageExecutions);
		}
	}
	private boolean worldFogApplied;
	private boolean worldFogRepeatLogged;

	/** Reset once per level frame, including frames with no sky pass. */
	public void beginWorldRenderFrame() {
		this.worldCloudPassReady = false;
		this.pendingStormFogView = null;
		this.pendingStormFogProjection = null;
		this.worldCloudStageStarted = false;
		this.worldCloudStageExecutions = 0;
		this.pipelinePrepared = false;
		var selected = nonamecrackers2.crackerslib.common.compat.CompatHelper.areShadersRunning()
				? CloudsRenderPipeline.SHADER_SUPPORT : CloudsRenderPipeline.DEFAULT;
		// Distant Horizons pastes its LODs over everything already in the frame, so with DH
		// the clouds are drawn after it, as the 1.20.1 original did (DhSupportPipeline).
		if (selected == CloudsRenderPipeline.DEFAULT && dev.nonamecrackers2.simpleclouds.SimpleCloudsMod.dhLoaded()
				&& !("1".equals(System.getenv("SIMPLECLOUDS_DEV")) && "1".equals(System.getenv("SIMPLECLOUDS_TEST_NO_DH_DEFER"))))
			selected = dev.nonamecrackers2.simpleclouds.client.dh.pipeline.DhSupportPipeline.INSTANCE;
		var selection = new dev.nonamecrackers2.simpleclouds.client.event.impl.DetermineCloudRenderPipelineEvent(selected);
		net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(selection);
		this.renderPipelineThisPass = selection.getOverridenPipeline() == null ? selected : selection.getOverridenPipeline();
		this.deferCloudsForDh = this.renderPipelineThisPass == dev.nonamecrackers2.simpleclouds.client.dh.pipeline.DhSupportPipeline.INSTANCE;
		this.dhSkyStageDone = false;
		if (selected == CloudsRenderPipeline.SHADER_SUPPORT && "1".equals(System.getenv("SIMPLECLOUDS_DEV")) && !this.shaderSelectionLogged) {
			this.shaderSelectionLogged = true;
			LOGGER.info("[PIPELINE-SELECTION] shadersRunning=true override={}", selection.getOverridenPipeline() != null);
		}
		this.worldFogApplied = false;
		capturedWeatherThisFrame = false;
		CAPTURED_WEATHER.reset();
	}

	/** Effects requiring completed terrain depth; no mesh generation or cloud redraw. */
	public void renderAfterLevel(float partialTick, double camX, double camY, double camZ)
	{
		if (!SimpleCloudsCompatHelper.renderThisPass()) return;
		if (this.mc.isPaused()) partialTick = this.lastUnpausedPartialTick;
		if (this.mc.gui.screen() instanceof CloudPreviewerScreen previewScreen)
		{
			if (this.drawPipeline != null) this.drawPipeline.beginFrame();
			this.drawPreviewInWorld(previewScreen, camX, camY, camZ);
			return;
		}
		if (!this.worldCloudPassReady) {
			PoseStack lateStack = new PoseStack();
			lateStack.last().pose().set(takeViewRotation(this.mc.gameRenderer.mainCamera()));
			this.renderAfterLevel(lateStack, this.mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.projectionMatrix,
					partialTick, camX, camY, camZ);
			if (!(this.deferCloudsForDh && this.worldCloudPassReady)) {
			if (this.worldCloudPassReady && this.drawPipeline != null) {
				// PrecipitationQuad and LightningBolt supply absolute world vertices,
				// unlike the camera-relative cloud API stack passed above.
				Matrix4f lateWeatherView = new Matrix4f(lateStack.last().pose())
						.translate((float)-camX, (float)-camY, (float)-camZ);
				if (SimpleCloudsCompatHelper.renderCustomRain())
					this.getWorldEffectsManager().renderRain(lateWeatherView, partialTick, camX, camY, camZ);
				if (this.getWorldEffectsManager().hasLightningToRender())
					this.getWorldEffectsManager().renderLightning(lateWeatherView, partialTick, camX, camY, camZ, this.drawPipeline);
			}
			return;
			}
		}
		if (this.drawPipeline == null) return;
		Matrix4f view = new Matrix4f(takeViewRotation(this.mc.gameRenderer.mainCamera()))
				.translate((float)-camX, (float)-camY, (float)-camZ);
		var driftManager = CloudManager.get(this.mc.level);
		int cloudHeight = driftManager != null ? driftManager.getCloudHeight() : 128;

		// Cloud shadows: top-down ortho depth pass over the cloud
		// instances, then a fullscreen terrain-shadow pass (see CloudShadowPass
		// section of CloudsDrawPipeline and PORTING.md).
		// A1: the shadow map draws the per-chunk buffers (one drawIndexed per chunk
		// inside a single pass).
		if (this.pendingStormFogView != null)
		{
			// Storm fog of a cloud stage drawn before the translucent stage: after water now.
			this.drawStormFogNow(this.pendingStormFogView, this.pendingStormFogProjection, camX, camY, camZ, partialTick);
			this.pendingStormFogView = null;
			this.pendingStormFogProjection = null;
		}
		this.drawPipeline.renderCloudShadowMap(camX, camY, camZ, (float) cloudHeight, this.shadowSources);
		if (overlaysEnabled)
		{
			// Step 6: the original's MinimumRadius — the render distance in blocks
			// (cloud_shadows shadows start at render distance + 32).
			float minimumRadius = mc.options.getEffectiveRenderDistance() * 16.0F;
			if (devMinRadius >= 0.0F) // SHADNEAR diagnostic
				minimumRadius = devMinRadius;
			this.drawPipeline.drawTerrainShadows(view, camX, camY, camZ, (float) cloudHeight, minimumRadius);
		}
		PoseStack fogStack = new PoseStack();
		fogStack.last().pose().set(takeViewRotation(this.mc.gameRenderer.mainCamera()));
		this.renderBeforeWeather(fogStack, this.mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.projectionMatrix,
				partialTick, camX, camY, camZ);
		if ("1".equals(System.getenv("SIMPLECLOUDS_DEV")) && "1".equals(System.getenv("SIMPLECLOUDS_TEST_REPEAT_FOG")))
		{
			this.renderBeforeWeather(fogStack, this.mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.projectionMatrix,
					partialTick, camX, camY, camZ);
			if (!this.worldFogRepeatLogged && this.drawPipeline.getWorldFogDrawsThisFrame() == 1) {
				this.worldFogRepeatLogged=true;
				LOGGER.info("[WORLD-FOG-REPLAY] two API calls, actualGpuPasses={}",this.drawPipeline.getWorldFogDrawsThisFrame());
			}
		}
		// Weather is drawn after cloud composition and terrain-dependent overlays.
		if (SimpleCloudsCompatHelper.renderCustomRain())
			this.getWorldEffectsManager().renderRain(view, partialTick, camX, camY, camZ);
		if (this.getWorldEffectsManager().hasLightningToRender())
			this.getWorldEffectsManager().renderLightning(view, partialTick, camX, camY, camZ, this.drawPipeline);

		// LAST: vanilla's rain and snow, over the clouds and every overlay above them.
		if (redrawsVanillaWeather())
			this.drawVanillaWeatherAfterClouds(view);
		this.renderAfterLevel(fogStack, this.mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.projectionMatrix,
				partialTick, camX, camY, camZ);
		if (this.dhDepthMerged) {
			this.drawPipeline.restoreMainDepth();
			this.dhDepthMerged = false;
		}
	}

	/** Original after-sky layer, before terrain, precipitation and voxel clouds. */
	public void renderAtmosphereAfterSky()
	{
		if (this.drawPipeline == null || this.atmosphericClouds == null || !overlaysEnabled
				|| !SimpleCloudsConfig.CLIENT.atmosphericClouds.get()
				|| !canRenderInDimension(this.mc.level) || !SimpleCloudsCompatHelper.renderThisPass()
				|| this.mc.gui.screen() instanceof CloudPreviewerScreen)
			return;
		var camera = this.mc.gameRenderer.mainCamera();
		var pos = camera.position();
		Matrix4f projection = this.mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.projectionMatrix;
		Matrix4f view = new Matrix4f(takeViewRotation(camera)).translate((float)-pos.x, (float)-pos.y, (float)-pos.z);
		float partialTick = this.mc.isPaused() ? this.lastUnpausedPartialTick
				: this.mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		float[] color = this.getCloudColor(partialTick);
		this.atmosphericClouds.render(this.drawPipeline, view, projection, partialTick, color[0], color[1], color[2]);
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
	public int getDeferredWeatherReplaysThisFrame() {
		return this.drawPipeline == null ? 0 : this.drawPipeline.getVanillaWeatherReplaysThisFrame();
	}
	public int getWorldFogDrawsThisFrame() {
		return this.drawPipeline == null ? 0 : this.drawPipeline.getWorldFogDrawsThisFrame();
	}

	// Plan item 3: spatial storm-fog coverage around the camera, and up to MAX_BOLTS live bolts
	// that light the fog ([x, y, z, strength, r, g, b, radius] each), nearest first. Every bolt is
	// logged once with the fog light it gets, so the S5 strikes at 200/1500/3000/8000 blocks are
	// proof data for "a far strike lights nothing".
	private final float[] fogBolts = new float[dev.nonamecrackers2.simpleclouds.client.renderer.v2.OriginalStormFogUniforms.MAX_BOLTS * 4];
	private int fogBoltCount;
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

	private void collectFogBolts(float partialTick, boolean lightOn)
	{
		this.fogBoltCount = 0;
		if (!lightOn) return;
		// Preserve original list order, 16 records and XZ light radius in the shader.
		this.getWorldEffectsManager().forLightning(bolt ->
		{
			if (this.fogBoltCount >= dev.nonamecrackers2.simpleclouds.client.renderer.v2.OriginalStormFogUniforms.MAX_BOLTS) return;
			org.joml.Vector3f p = bolt.getPosition();
			int o = this.fogBoltCount++ * 4;
			this.fogBolts[o] = p.x;
			this.fogBolts[o + 1] = p.y;
			this.fogBolts[o + 2] = p.z;
			this.fogBolts[o + 3] = bolt.getFade(partialTick);
		});
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
		this.closeOriginalGenerator();
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
		this.destroyPreview();
		this.closeOriginalGenerator();
		this.ensurePipeline();
		this.needsReload = false;
	}

	public void onMainWindowResize(int width, int height)
	{
		// TODO(26.2): nothing to resize for the vertical slice (main target is managed by the game).
	}

	public void shutdown() {
		this.destroyPreview();
		this.closeOriginalGenerator();
		if(this.drawPipeline!=null) {this.drawPipeline.close();this.drawPipeline=null;}
		if(this.rainPipeline!=null) {this.rainPipeline.close();this.rainPipeline=null;}
		this.worldEffects=null;
	}

	public void baseTick()
	{
		if (this.meshGenerator != null && !this.mc.isPaused()) this.meshGenerator.worldTick();
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

	public void renderCloudsAfterSky(PoseStack stack, Matrix4f projMat, float partialTick, double camX, double camY, double camZ)
	{
		this.renderCloudStage(true, stack, projMat, partialTick, camX, camY, camZ);
	}
	/**
	 * DH path: the deferred cloud stage runs between the opaque and translucent stages of the
	 * main pass (MixinLevelRenderer), so particles and weather blend over the clouds instead of
	 * punching holes in them. SIMPLECLOUDS_TEST_CLOUDS_AT_TAIL=1 (dev) keeps the TAIL draw.
	 */
	public boolean wantsCloudsBeforeTranslucent()
	{
		if (!this.deferCloudsForDh || this.worldCloudStageStarted || !this.dhSkyStageDone) return false;
		if (nonamecrackers2.crackerslib.common.compat.CompatHelper.areShadersRunning()) return false;
		if (this.mc.level == null || !canRenderInDimension(this.mc.level)) return false;
		return !("1".equals(System.getenv("SIMPLECLOUDS_DEV")) && "1".equals(System.getenv("SIMPLECLOUDS_TEST_CLOUDS_AT_TAIL")));
	}

	/** Set when this frame's clouds were drawn before the translucent stage (DH fade guard). */
	private boolean cloudsDrawnBeforeTranslucent;

	public void beginDhFadeGuard()
	{
		if (this.cloudsDrawnBeforeTranslucent && this.drawPipeline != null) this.drawPipeline.beginDhFadeGuard();
	}

	public void endDhFadeGuard()
	{
		if (this.cloudsDrawnBeforeTranslucent && this.drawPipeline != null) this.drawPipeline.endDhFadeGuard();
		this.cloudsDrawnBeforeTranslucent = false;
	}

	public void renderCloudsBeforeTranslucent()
	{
		var camera = this.mc.gameRenderer.mainCamera();
		var pos = camera.position();
		float partialTick = this.mc.getDeltaTracker() != null ? this.mc.getDeltaTracker().getGameTimeDeltaPartialTick(false) : 0.0F;
		Matrix4f projMat = this.mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.projectionMatrix;
		this.cloudsBeforeTranslucent = true;
		try
		{
			this.renderCloudStage(false, new PoseStack(), projMat, partialTick, pos.x, pos.y, pos.z);
			this.cloudsDrawnBeforeTranslucent = this.worldCloudPassReady;
		}
		finally
		{
			this.cloudsBeforeTranslucent = false;
		}
	}

	/** Shader pipeline owns late cloud composition; never replaces the shader sky. */
	public void renderCloudsAfterShaderLevel(PoseStack stack, Matrix4f projMat, float partialTick, double camX, double camY, double camZ)
	{
		this.renderCloudStage(false, stack, projMat, partialTick, camX, camY, camZ);
	}
	/** DH LOD depth into the main depth (Minecraft's projection) so late clouds, fog and
	 * weather are hidden behind DH terrain; restored after the after-level effects. */
	/** DevShot A/B: the original camera-facing face cull, for FPS comparison (developer runs only). */
	public static volatile boolean devCullAwayFaces;

	/** DevShot A/B: skip the transparent cube pass (developer runs only). */
	public static volatile boolean devSkipTransparency;

	/** DevShot A/B: skip the DH depth merge (developer runs only). */
	public static volatile boolean devDisableDhMerge;

	private void mergeDhDepth(Matrix4f projMat)
	{
		if (this.drawPipeline == null || projMat == null || this.dhDepthMerged || devDisableDhMerge) return;
		if ("1".equals(System.getenv("SIMPLECLOUDS_DEV")) && "1".equals(System.getenv("SIMPLECLOUDS_TEST_NO_DH_MERGE"))) return;
		var depth = dev.nonamecrackers2.simpleclouds.client.dh.SimpleCloudsDhCompatHandler._getDhDepthView();
		var inverse = dev.nonamecrackers2.simpleclouds.client.dh.SimpleCloudsDhCompatHandler._getLastDhInverseProjMat();
		if (depth == null || inverse == null) return;
		this.dhDepthMerged = this.drawPipeline.mergeDhDepth(depth, inverse, projMat);
	}

	private void renderCloudStage(boolean atmospheric, PoseStack stack, Matrix4f projMat, float partialTick, double camX, double camY, double camZ)
	{
		if (!SimpleCloudsCompatHelper.renderThisPass() || this.worldCloudStageStarted)
			return;
		boolean skyOnly = atmospheric && this.deferCloudsForDh;
		boolean afterDhSky = !atmospheric && this.dhSkyStageDone;
		if (skyOnly && this.dhSkyStageDone) return;
		if (!skyOnly) this.worldCloudStageStarted = true;
		if (this.mc.isPaused()) partialTick = this.lastUnpausedPartialTick;
		else this.lastUnpausedPartialTick = partialTick;
		if (this.drawPipeline == null) this.ensurePipeline();
		// One reset per rendered frame of the pipeline's private transform ring (see
		// CloudsDrawPipeline.beginFrame): every cloud, shadow, lightning, storm and transition
		// draw of this frame writes its slice after it.
		if (this.drawPipeline != null && !afterDhSky)
			this.drawPipeline.beginFrame();
		// The original tints every cloud pass with vanilla's time/weather cloud
		// color, then applies local storm darkening and lightning brightening.
		if (this.drawPipeline != null)
		{
			float[] cloudColor = this.getCloudColor(partialTick);
			this.drawPipeline.setCloudColor(cloudColor[0], cloudColor[1], cloudColor[2]);
		}
		// Editor target remains in the after-level path, not the sky graph pass.
		if (this.mc.gui.screen() instanceof CloudPreviewerScreen) return;
		if (atmospheric) this.renderAtmosphereAfterSky();
		if (skyOnly) { this.dhSkyStageDone = true; return; }
		if (this.deferCloudsForDh) this.mergeDhDepth(projMat);
		this.generateAndDrawClouds(camX, camY, camZ, partialTick, projMat);
		if (this.worldCloudPassReady) this.worldCloudStageExecutions++;
		if (!atmospheric && this.worldCloudPassReady && "1".equals(System.getenv("SIMPLECLOUDS_DEV")) && !this.shaderStageLogged) {
			this.shaderStageLogged = true;
			LOGGER.info("[SHADER-CLOUD-STAGE] completed=true atmosphere=false");
		}
	}

	/**
	 * Render the original orthographic editor target before GUI controls are drawn.
	 */
	private boolean drawPreviewInWorld(Screen3D s3d, double camX, double camY, double camZ)
	{
		try
		{
			if (this.drawPipeline == null)
				return false;
			if (this.previewPipeline == null)
			{
				this.previewPipeline = new PreviewDrawPipeline();
			}
			if (s3d instanceof dev.nonamecrackers2.simpleclouds.client.gui.CloudPreviewerScreen preview
					&& this.previewType != preview.selectedCloudType()) {
				this.previewPipeline.generateMesh(preview.selectedCloudType());
				this.previewType=preview.selectedCloudType();
			}
			Matrix4f view = dev.nonamecrackers2.simpleclouds.client.renderer.v2.OriginalPreviewTransform.view(
					s3d.width, s3d.height, s3d.zoom(), s3d.zoomConstant(), s3d.farPlane(),
					s3d.camRotX(), s3d.camRotY(), s3d.offset());
			var window = this.mc.getWindow();
			Matrix4f projection = dev.nonamecrackers2.simpleclouds.client.renderer.v2.OriginalPreviewTransform.projection(
					(float)(window.getWidth() / window.getGuiScale()),
					(float)(window.getHeight() / window.getGuiScale()), s3d.farPlane());
			this.previewPipeline.draw(this.drawPipeline, view, projection);
			if(this.pendingPreviewExport!=null) {
				var request=this.pendingPreviewExport;
				var exported=dev.nonamecrackers2.simpleclouds.client.gui.CloudPreviewExport.save(
						this.drawPipeline.resolvePreviewForExport(),this.mc.gameDirectory.toPath());
				this.pendingPreviewExport=null;
				exported.whenComplete((path,error)->{
							if(error!=null) request.completeExceptionally(error); else request.complete(path);
						});
			}
		}
		catch (Throwable t)
		{
			if(this.pendingPreviewExport!=null) {
				this.pendingPreviewExport.completeExceptionally(t); this.pendingPreviewExport=null;
			}
			if (!this.previewLoggedError)
			{
				this.previewLoggedError = true;
				LOGGER.error("Simple Clouds: preview render pass failed (further failures suppressed)", t);
			}
			return false;
		}
		return true;
	}

	public void renderMenuPreview(net.minecraft.client.gui.GuiGraphicsExtractor graphics,CloudPreviewerScreen screen) {
		if(this.mc.level!=null) return;
		if(this.drawPipeline==null) this.ensurePipeline();
		if(this.drawPipeline==null) return;
		this.drawPipeline.beginFrame();
		if(!this.drawPreviewInWorld(screen,0,0,0)) return;
		var target=this.drawPipeline.resolvePreviewForExport();
		// GUI-extracted texture, before editor widgets. A direct main-target draw
		// would be overwritten by the later title-screen/GUI render pass.
		graphics.blit(target.getColorTextureView(),this.drawPipeline.previewSampler(),0,0,screen.width,screen.height,0f,1f,1f,0f);
	}

	private boolean previewLoggedError = false;
	private java.util.concurrent.CompletableFuture<java.nio.file.Path> pendingPreviewExport;
	public java.util.concurrent.CompletableFuture<java.nio.file.Path> exportPreviewImage() {
		com.mojang.blaze3d.systems.RenderSystem.assertOnRenderThread();
		if(this.pendingPreviewExport!=null) return java.util.concurrent.CompletableFuture.failedFuture(
				new IllegalStateException("Preview export already pending"));
		this.pendingPreviewExport=new java.util.concurrent.CompletableFuture<>();
		return this.pendingPreviewExport;
	}
	private dev.nonamecrackers2.simpleclouds.common.cloud.CloudType previewType;

	/** Called when the previewer screen closes (MixinGameRenderer hook path). */
	public void destroyPreview()
	{
		if(this.pendingPreviewExport!=null) {
			this.pendingPreviewExport.completeExceptionally(new IllegalStateException("Preview closed before export"));
			this.pendingPreviewExport=null;
		}
		this.previewType=null;
		if (this.previewPipeline != null)
		{
			this.previewPipeline.close();
			this.previewPipeline = null;
		}
	}

	public void renderAfterSky(PoseStack stack, Matrix4f projMat, float partialTick, double camX, double camY, double camZ)
	{
		if (!SimpleCloudsCompatHelper.renderThisPass()) return;
		var profiler = net.minecraft.util.profiling.Profiler.get();
		profiler.push("simple_clouds_after_sky");
		try {
			this.prepareSelectedPipeline(stack, projMat, partialTick, camX, camY, camZ);
			this.getRenderPipeline().afterSky(this.mc, this, stack, projMat, partialTick, camX, camY, camZ, this.cullFrustum);
		} finally {
			profiler.pop();
		}
	}

	public void renderBeforeWeather(PoseStack stack, Matrix4f projMat, float partialTick, double camX, double camY, double camZ)
	{
		if (!SimpleCloudsCompatHelper.renderThisPass()) return;
		var profiler = net.minecraft.util.profiling.Profiler.get();
		profiler.push("simple_clouds_before_weather");
		try {
			this.getRenderPipeline().beforeWeather(this.mc, this, stack, projMat, partialTick, camX, camY, camZ, this.cullFrustum);
		} finally {
			profiler.pop();
		}
	}

	public void renderAfterLevel(PoseStack stack, Matrix4f projMat, float partialTick, double camX, double camY, double camZ)
	{
		if (!SimpleCloudsCompatHelper.renderThisPass()) return;
		var profiler = net.minecraft.util.profiling.Profiler.get();
		profiler.push("simple_clouds_after_level");
		try {
			this.prepareSelectedPipeline(stack, projMat, partialTick, camX, camY, camZ);
			this.getRenderPipeline().afterLevel(this.mc, this, stack, projMat, partialTick, camX, camY, camZ, this.cullFrustum);
		} finally {
			profiler.pop();
		}
	}
	private void prepareSelectedPipeline(PoseStack stack, Matrix4f projection, float partialTick, double x, double y, double z) {
		if (this.pipelinePrepared) return;
		this.pipelinePrepared = true;
		this.cullFrustum = new Frustum(stack.last().pose(), projection);
		var manager = CloudManager.get(this.mc.level);
		this.cullFrustum.prepare(x / CLOUD_SCALE_F, (y - manager.getCloudHeight()) / CLOUD_SCALE_F, z / CLOUD_SCALE_F);
		this.getRenderPipeline().prepare(this.mc, this, stack, projection, partialTick, x, y, z, this.cullFrustum);
	}

	/** @deprecated Legacy GL post-chain API; use the frame stage API documented in docs/renderer-api-26.3.md. */
	@Deprecated(since="26.3", forRemoval=true)
	public void doBlurPostProcessing(float partialTick)
	{
		throw legacyRenderStage("doBlurPostProcessing");
	}

	public void doScreenSpaceWorldFog(PoseStack stack, Matrix4f projMat, float partialTick)
	{
		if (this.drawPipeline == null || !this.worldCloudPassReady || this.worldFogApplied
				|| !dev.nonamecrackers2.simpleclouds.client.FogColorCapturer.hasRanges()) return;
		this.worldFogApplied = this.drawPipeline.drawWorldFog(projMat, stack.last().pose(),
				dev.nonamecrackers2.simpleclouds.client.FogColorCapturer.start(),
				dev.nonamecrackers2.simpleclouds.client.FogColorCapturer.end(),
				dev.nonamecrackers2.simpleclouds.client.FogColorCapturer.get());
	}

	@Deprecated(since="26.3", forRemoval=true)
	public void doFinalCompositePass(PoseStack stack, float partialTick, Matrix4f projMat)
	{
		throw legacyRenderStage("doFinalCompositePass");
	}

	@Deprecated(since="26.3", forRemoval=true)
	public void doStormPostProcessing(PoseStack stack, float partialTick, Matrix4f projMat, double camX, double camY, double camZ, float r, float g, float b)
	{
		throw legacyRenderStage("doStormPostProcessing");
	}

	@Deprecated(since="26.3", forRemoval=true)
	public void doCloudShadowProcessing(PoseStack stack, float partialTick, Matrix4f projMat, double camX, double camY, double camZ, int depthBufferId)
	{
		throw legacyRenderStage("doCloudShadowProcessing");
	}

	@Deprecated(since="26.3", forRemoval=true)
	public void copyDepthFromCloudsToMain()
	{
		throw legacyRenderStage("copyDepthFromCloudsToMain");
	}

	@Deprecated(since="26.3", forRemoval=true)
	public void copyDepthFromMainToClouds()
	{
		throw legacyRenderStage("copyDepthFromMainToClouds");
	}

	@Deprecated(since="26.3", forRemoval=true)
	public void copyDepthFromCloudsToTransparency()
	{
		throw legacyRenderStage("copyDepthFromCloudsToTransparency");
	}

	private static UnsupportedOperationException legacyRenderStage(String method)
	{
		return new UnsupportedOperationException(method + " is the removed legacy GL post-chain API. "
				+ "26.3 cloud/depth/composite stages are owned by CloudsDrawPipeline; migrate custom pipelines "
				+ "using docs/renderer-api-26.3.md. Do not call a whole-frame renderer once per legacy stage.");
	}

	public void fillReport(CrashReport report)
	{
		var category=report.addCategory("Simple Clouds Renderer");
		category.setDetail("Cloud Mode", () -> String.valueOf(this.settings.getCurrentCloudMode()));
		category.setDetail("GPU Draw Pipeline Available", this.drawPipeline!=null);
		category.setDetail("Original GPU Draw Buffers Available", this.originalDrawBuffers!=null);
		category.setDetail("Atmospheric Renderer Available", this.atmosphericClouds!=null);
		category.setDetail("Rain Pipeline Available", this.rainPipeline!=null);
		category.setDetail("Preview Pipeline Available", this.previewPipeline!=null);
		category.setDetail("Preview Export Pending", this.pendingPreviewExport!=null);
		category.setDetail("Needs Reload", this.needsReload);
		category.setDetail("World Pass Ready", this.worldCloudPassReady);
		category.setDetail("World Fog Applied", this.worldFogApplied);
		category.setDetail("Selected Pipeline", () -> String.valueOf(this.renderPipelineThisPass));
		var mesh=report.addCategory("Cloud Mesh Generator");
		if(this.meshGenerator==null) mesh.setDetail("Type", "Mesh generator is not initialized");
		else {
			mesh.setDetail("Type", () -> this.meshGenerator.getClass().getName());
			this.meshGenerator.fillReport(mesh);
		}
	}
}

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
		// TODO(26.2): restore the dimension-whitelist check against the ported config.
		return level != null;
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
			this.cpuGenerator = new CpuCloudGenerator(List.of()); // groups are set per-band from the cloud type data
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
	 * quantized to 1/4 cloud unit, 1/100 rad); the mesh is only regenerated when this or
	 * the band origin changes.
	 */
	private long regionSignature(float partialTick)
	{
		if (this.cloudManager == null)
			return 0L;
		long sig = 1L;
		for (dev.nonamecrackers2.simpleclouds.common.cloud.region.CloudRegion region : this.cloudManager.getClouds())
		{
			sig = 31L * sig + region.getCloudTypeId().hashCode();
			sig = 31L * sig + (long) (region.getPosX(partialTick) * 4.0F);
			sig = 31L * sig + (long) (region.getPosZ(partialTick) * 4.0F);
			sig = 31L * sig + (long) (region.getRadius(partialTick) * 4.0F);
			sig = 31L * sig + (long) (region.getStretch(partialTick) * 100.0F);
			sig = 31L * sig + (long) (region.getRotation(partialTick) * 100.0F);
		}
		return sig;
	}

	// Generation cache: the cloud field is world-fixed; the expensive CPU band is only
	// regenerated when the band origin (a 2-block grid cell) or the data-driven group
	// set changes.
	private int cacheX0 = Integer.MIN_VALUE, cacheY0 = Integer.MIN_VALUE, cacheZ0 = Integer.MIN_VALUE;
	private int cacheGroupsHash = Integer.MIN_VALUE;
	private long cacheRegionSig = Long.MIN_VALUE;
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

		// Grid scale = 2 blocks/cell; the band is a 32x32 world-block patch around the
		// camera in X/Z. Clouds are WORLD-FIXED: the noise is a pure function of world
		// coordinates (zero scroll), only the generated band follows the camera (the
		// original culled by view distance/formation bounds; here the field is infinite).
		// Grid scale = CLOUD_SCALE = 8 world blocks/cell (the original's cloud unit: the
		// region positions/radii and the noise coordinates live in this space). The band
		// is 32x32 units (256x256 world blocks) around the camera in X/Z — the original's
		// TILE_PERIOD — and fixed in Y at 0..64 units (0..512 blocks), covering every
		// type's noise heights (CLOUD_HEIGHT 128 .. ~512).
		float scale = 8.0F;
		int span = 16;
		int x0 = Mth.floor(camX / scale) - span;
		int z0 = Mth.floor(camZ / scale) - span;
		int camGridY = Mth.floor(camY / scale);
		int y0 = 0;
		int y1 = 64;

		List<CpuCloudGenerator.CloudLayerGroup> groups = dataDrivenGroups();
		int groupsHash = groups.hashCode();
		// Formation footprints: the original's multi-region path masks the same
		// world-fixed noise field by the spawned formations' X/Z coverage. With no
		// formations (not synced yet / vanilla weather) the generator falls back to
		// the infinite field.
		java.util.Map<net.minecraft.resources.Identifier, Integer> typeToGroup = dataDrivenGroupIndices();
		List<CpuCloudGenerator.RegionMask> regions = this.buildRegionMasks(typeToGroup, partialTick);
		long regionSig = this.regionSignature(partialTick);
		if (x0 != this.cacheX0 || z0 != this.cacheZ0 || groupsHash != this.cacheGroupsHash || regionSig != this.cacheRegionSig)
		{
			this.cpuGenerator.setGroups(groups);
			this.cpuGenerator.setRegions(regions);
			float[] opaqueCount = new float[1];
			float[] transparentCount = new float[1];
			float[] stormCoverage = new float[1];
			java.nio.ByteBuffer[] data = this.cpuGenerator.generate(
					x0, y0, z0, x0 + span * 2, y1, z0 + span * 2,
					scale, 0.0F, 0.0F, 0.0F, 0.0F, opaqueCount, transparentCount, camGridY, stormCoverage);
			this.drawPipeline.setInstances(data[0], (int) opaqueCount[0]);
			// Config gate (1.20.1's renderCloudsTransparency only ran when transparency
			// was enabled in the client config).
			boolean transparencyEnabled = SimpleCloudsConfig.CLIENT.transparency.get();
			this.drawPipeline.setTransparencyInstances(
					transparencyEnabled ? data[1] : null,
					transparencyEnabled ? (int) transparentCount[0] : 0);
			this.cacheX0 = x0;
			this.cacheY0 = y0;
			this.cacheZ0 = z0;
			this.cacheGroupsHash = groupsHash;
			this.cacheRegionSig = regionSig;
			this.cacheStormCoverage = stormCoverage[0];
			LOGGER.info("Simple Clouds clouds: regenerated, {} formations, {} opaque instances", regions.size(), (int) opaqueCount[0]);
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
		this.drawPipeline.renderCloudShadowMap(camX, camZ);
		this.drawPipeline.drawTerrainShadows(view, camX, camZ);
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
		// TODO(26.2): port atmospheric clouds.
		return null;
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
		this.cpuGenerator = null;
		this.worldEffects = null;
	}

	public void baseTick()
	{
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

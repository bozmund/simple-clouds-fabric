package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.OptionalDouble;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.jetbrains.annotations.Nullable;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

import dev.nonamecrackers2.simpleclouds.SimpleCloudsMod;
import dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig;

/**
 * Vertical-slice (26.2) cloud render pipeline: builds the {@link RenderPipeline}
 * for the clouds shader, owns the base quad mesh + per-instance buffer + UBOs,
 * and draws instanced cloud faces into the main render target.
 */
public class CloudsDrawPipeline implements AutoCloseable
{
	private static final Logger LOGGER = LogManager.getLogger("simpleclouds/Pipeline");
	private static final Identifier CLOUDS_LOCATION = SimpleCloudsMod.id("core/clouds");
	// 26.2: shader sources are keyed by BARE location + ShaderType — the device appends the
	// .vsh/.fsh extension itself (vanilla uses e.g. "core/rendertype_clouds" for both).
	private static final Identifier CLOUDS_VSH = CLOUDS_LOCATION;
	private static final Identifier CLOUDS_FSH = CLOUDS_LOCATION;
	// 16x16 dither matrix for cloud edge fading (same texture the 1.20.1 mod shipped).
	private static final Identifier BAYER_TEXTURE = SimpleCloudsMod.id("textures/shader/bayer_matrix.png");
	// Transparency pass (cloud edges just below the opaque threshold), 26.2 location.
	private static final Identifier CLOUDS_TRANSPARENCY_LOCATION = SimpleCloudsMod.id("core/clouds_transparency");
	// Storm fog overlay (26.2 slice: fullscreen blend pass, see core/storm_fog.fsh).
	private static final Identifier STORM_FOG_LOCATION = SimpleCloudsMod.id("core/original_storm_fog");
	private static final Identifier SKY_FLASH_LOCATION = SimpleCloudsMod.id("core/sky_flash");
	private RenderPipeline transitionPipeline;
	private com.mojang.blaze3d.pipeline.TextureTarget transitionOld, transitionNew;
	private RenderTarget cloudDestination;
	private com.mojang.blaze3d.pipeline.TextureTarget previewTarget;
	private com.mojang.blaze3d.pipeline.TextureTarget previewExportTarget;
	private GpuBuffer previewProjection, previewFog;
	private boolean previewActive;
	private final RenderPipeline previewCompositePipeline;
	private final RenderPipeline previewExportPipeline;
	private com.mojang.blaze3d.pipeline.TextureTarget atmosphericSource;
	private final RenderPipeline worldFogPipeline;
	private final RenderPipeline dhFadeGuardPipeline;
	private com.mojang.blaze3d.pipeline.TextureTarget dhFadeGuardSaved;
	private boolean dhFadeGuardArmed;
	private final GpuBuffer[] worldFogRing = new GpuBuffer[3];
	private int worldFogSlot;
	private com.mojang.blaze3d.pipeline.TextureTarget worldFogSource, cloudDepthSnapshot, preCloudDepthSnapshot;
	private boolean cloudDepthReady, stormFogReady;
	private boolean worldFogLogged;
	private int worldFogDraws;
	private int vanillaWeatherReplays;
	public int getVanillaWeatherReplaysThisFrame() { return this.vanillaWeatherReplays; }
	public int getWorldFogDrawsThisFrame() { return this.worldFogDraws; }
	private boolean worldFogStormLogged;
	// Screen-covering triangle in clip space.
	private static final float[] FULLSCREEN_TRIANGLE = { -1.0F, -1.0F, 3.0F, -1.0F, -1.0F, 3.0F };

	// Base quad (from InstanceableMesh.defaultSide): x=-1 plane, y/z in [-1,1].
	private static final float[] QUAD_VERTICES = {
			-1.0F, -1.0F, 1.0F,
			-1.0F, -1.0F, -1.0F,
			-1.0F, 1.0F, -1.0F,
			-1.0F, 1.0F, 1.0F,
	};
	private static final int[] QUAD_INDICES = { 0, 1, 2, 0, 2, 3 };
	// Exact original InstanceableMesh.defaultCube vertex/index order.
	private static final float[] CUBE_VERTICES = {
		-1,-1,-1, 1,-1,-1, 1,1,-1, -1,1,-1,
		1,-1,1, 1,1,1, -1,1,1, -1,-1,1
	};
	private static final int[] CUBE_INDICES = {
		0,1,2,0,2,3, 4,7,6,4,6,5, 7,0,3,7,3,6,
		1,4,5,1,5,2, 1,0,7,1,7,4, 5,6,3,5,3,2
	};

	private final RenderPipeline pipeline;
	private final RenderPipeline transparencyPipeline;
	private final RenderPipeline transparencyMrtPipeline;
	private final RenderPipeline transparencyRevealagePipeline;
	private final RenderPipeline transparencyCompositePipeline;
	private com.mojang.blaze3d.pipeline.TextureTarget transparencyAccum, transparencyRevealage;
	private boolean transparencyActive;
	private record TransparentDraw(GpuBuffer instances, int count, GpuBufferSlice transforms, GpuBufferSlice clip) { }
	// Draw metadata only: geometry stays in the original GPU buffers. All uniform
	// uploads finish before either pass opens, as required by RenderPearl.
	private final List<TransparentDraw> transparentDraws = new ArrayList<>();
	private final GpuBuffer quadVertexBuffer;
	private final GpuBuffer quadIndexBuffer;
	private final GpuBuffer cubeVertexBuffer;
	private final GpuBuffer cubeIndexBuffer;
	private final GpuBuffer lightingUbo;
	private final GpuBuffer shadingUbo;

	// Lightning bolts (1.20.1 port): world-space quads, additive blend, depth
	// tested against terrain, no depth write. One dynamic vertex upload per frame.
	private static final com.mojang.renderpearl.api.vertex.VertexFormat LIGHTNING_FORMAT = com.mojang.renderpearl.api.vertex.VertexFormat.builder(0)
			.addAttribute("Position", GpuFormat.RGB32_FLOAT)
			.addAttribute("Color", GpuFormat.RGBA32_FLOAT)
			.build();
	private static final Identifier LIGHTNING_LOCATION = SimpleCloudsMod.id("core/lightning");
	private final RenderPipeline lightningPipeline;
	// mat4 inverse DH projection + vec4 (enabled, 1/width, 1/height, unused), std140.
	private static final int LIGHTNING_OCCLUSION_BYTES = 80;
	private final GpuBuffer[] lightningOcclusionRing = new GpuBuffer[3];
	private int lightningOcclusionSlot;
	private GpuBuffer lightningVertexBuffer;
	private final GpuBuffer lightningIndexBuffer;
	private final GpuBuffer fogUbo;
	// Per-frame ring for the runtime-toggleable CloudShading.UseNormals (config
	// cubeNormals): the static shading UBO cannot be remapped every frame (26.2
	// per-frame-UBO rule), so the toggle rides on a ring.
	// Both shading records are immutable; live config chooses which to bind.
	// Rewriting identical data per chunk needlessly synchronizes queued draws.
	private final GpuBuffer[] shadingModes = new GpuBuffer[2];
	private static final float[] CLOUD_SHADING_BASE = { 0.0F, 0.0F, 0.15F };
	private final RenderPipeline stormFogPipeline;
	private final GpuBuffer triangleBuffer;
	private final GpuBuffer[] stormFogRing = new GpuBuffer[3];
	private int stormFogSlot;
	private final RenderPipeline stormCompositePipeline;
	// Distant Horizons LOD depth written into the main depth before the late cloud pass.
	private final RenderPipeline dhDepthMergePipeline;
	private final RenderPipeline dhDepthMergeDebugPipeline;
	private final GpuBuffer[] dhDepthMergeRing = new GpuBuffer[3];
	private int dhDepthMergeSlot;
	private com.mojang.blaze3d.pipeline.TextureTarget dhDepthScratch;
	private com.mojang.blaze3d.pipeline.TextureTarget dhDepthBackup;
	private final RenderPipeline stormUpscalePipeline;
	private final RenderPipeline stormBlurPipeline;
	private final GpuBuffer[] stormBlurRing = new GpuBuffer[18];
	private final GpuSampler stormLinearSampler;
	private com.mojang.blaze3d.pipeline.TextureTarget originalStormFogTarget;
	private com.mojang.blaze3d.pipeline.TextureTarget stormBlurMain, stormBlurSwap;
	private final RenderPipeline skyFlashPipeline;
	private final GpuBuffer skyFlashUbo;

	// Cloud shadow map (26.2 slice): top-down ortho depth pass over the same
	// instances + a fullscreen terrain-shadow pass. See core/clouds_shadow.* and
	// core/terrain_shadows.* for the shaders and PORTING.md for the slice scope.
	private static final int SHADOW_SIZE = 512; // 1 block/texel over the 512x512 block field
	// Step 1 (VISUAL-PARITY-PLAN): the light volume is anchored at WORLD cloudHeight
	// (like the original's shadow stack: translate(-camOffsetX, -cloudHeight,
	// -camOffsetZ)), NOT at the camera. The light plane sits at the top of the cloud
	// volume; the depth range covers the whole volume plus margin down to terrain.
	// (XZ radius 256 blocks is a known slice simplification -- revisit in step 6.)
	/** Step 6: the original's shadow span (cloud_shadows: shadowDistance*2, config
	 *  default 2500, clamped to the field span) — HALF the span, since the ortho
	 *  is ±R around the camera. 512 map / 5000 span ≈ 9.8 blocks/texel, the
	 *  original's resolution. */
	private static final float SHADOW_RADIUS = 2500.0F; // world blocks XZ around the camera
	// Step 6: the light plane sits just above the TOP of the cloud volume (the
	// volume base is cloudHeight; the layer heights reach ~196 blocks above it,
	// see the "generated Y range 156..324" proof log) — not 2048 above it. The
	// old 2048-block placement put the clouds (viewZ ≈ -2924) and the terrain
	// (viewZ ≈ -3188) OUTSIDE the 0..-2560 depth frustum, so every fragment was
	// clipped and the shadow map was 100% empty (no terrain shadow at all).
	private static final float SHADOW_VOLUME_TOP = 248.0F; // light plane above cloudHeight
	private static final float SHADOW_VOLUME_BELOW = 512.0F; // depth below cloudHeight (terrain)
	private static final float SHADOW_BIAS = 0.005F;
	/** Step 6: the original's FadeDistance (cloud_shadows.json default 1028). */
	private static final float SHADOW_FADE_DISTANCE = 1028.0F;
	/** Step 6 diagnostic (DevShot SHADOWDBG): output the stored shadow depth as red. */
	public static volatile float DEBUG_SHOW_DEPTH = 0.0F;
	/** A/B test switch: false = skip the terrain-shadow pass entirely. */
	public static boolean TERRAIN_SHADOWS_ENABLED = true;
	private static final Identifier CLOUDS_SHADOW_LOCATION = SimpleCloudsMod.id("core/clouds_shadow");
	private static final Identifier TERRAIN_SHADOWS_LOCATION = SimpleCloudsMod.id("core/terrain_shadows");
	private static final Identifier ATMOSPHERIC_CLOUDS_LOCATION = SimpleCloudsMod.id("core/atmospheric_clouds");

	// RenderTarget is abstract in 26.2 but has no abstract members.
	private static final class SimpleRenderTarget extends RenderTarget
	{
		SimpleRenderTarget(String label, boolean useDepth, GpuFormat format)
		{
			super(label, format, useDepth ? GpuFormat.D32_FLOAT : null);
		}
	}

	private final SimpleRenderTarget shadowTarget;
	private final SimpleRenderTarget stormShadowTarget;
	private final RenderPipeline stormShadowPipeline;
	private final GpuBuffer[] stormShadowMatricesRing = new GpuBuffer[3];
	private final GpuBuffer[] stormShadowParamsRing = new GpuBuffer[3];
	private int stormShadowSlot;
	private boolean stormShadowRendered;
	private final Matrix4f stormShadowView = new Matrix4f();
	private final Matrix4f stormShadowProjection = new Matrix4f();
	private final RenderPipeline shadowPipeline;
	private final RenderPipeline terrainPipeline;
	// Per-frame shadow uniforms. 26.2 idiom: a MappableRingBuffer (like
	// DynamicUniforms) -- mapping/unmapping ONE fixed buffer every frame and
	// binding it in a pass encoded later in the same frame silently kills the
	// pass (verified on screen: magenta probe invisible with the fixed-buffer
	// UBO, visible with a ring/fresh buffer).
	// Per-frame shadow uniform ring. MappableRingBuffer's createBuffer(name, usage,
	// size) path (immediate glMapBuffer in GlBuffer$Direct) fails consistently on
	// this Mesa/Intel ARL machine ("Failed to map buffer"); the data-carrying
	// createBuffer overload works (all other UBOs use it), so build the ring
	// manually from zero-initialized data buffers.
	// Step 5: per-chunk scroll-offset ring (see drawClouds). Rotates like
	// the old mutable shading ring: mapping a fixed buffer within a frame and binding it in a
	// pass encoded later in that same frame silently kills the pass on this
	// Mesa/Intel ARL machine (shadowMatricesRing note), so never reuse a slot
	// that a live pass still references.
	private final GpuBuffer[] offsetRing = new GpuBuffer[3];
	// Static zero offset for passes without scroll semantics (previewer box).
	private final GpuBuffer offsetZero;
	private final GpuBuffer clipDisabled;
	private final CloudClipRing cellClips = new CloudClipRing(TRANSFORM_SLOT_CAP);
	private int offsetSlotIdx = 0;

	private final GpuBuffer[] shadowMatricesRing = new GpuBuffer[3];
	private final GpuBuffer[] terrainPassRing = new GpuBuffer[3];
	private final GpuBuffer[] atmosphericRing = new GpuBuffer[3];
	private int shadowMatricesIdx = 0;
	private int terrainPassIdx = 0;
	private int atmosphericIdx = 0;
	private final RenderPipeline atmosphericPipeline;
	private final GpuSampler nearestSampler;
	private boolean shadowRenderedThisFrame = false;

	private GpuDevice device;

	public CloudsDrawPipeline()
	{
		this.device = RenderSystem.getDevice();
		GpuDevice device = this.device;

		BindGroupLayout bgl = BindGroupLayout.builder()
				.withUniform("CloudLighting", UniformType.UNIFORM_BUFFER)
				.withUniform("CloudShading", UniformType.UNIFORM_BUFFER)
				.withUniform("CloudFog", UniformType.UNIFORM_BUFFER)
				.withUniform("CloudOffset", UniformType.UNIFORM_BUFFER)
				.withUniform("CloudClip", UniformType.UNIFORM_BUFFER)
				.withUniform("BayerMatrixSampler", UniformType.COMBINED_IMAGE_SAMPLER)
				.build();

		// clouds.vsh/.fsh #moj_import vanilla's dynamictransforms.glsl (ModelViewMat,
		// ColorModulator) and projection.glsl (ProjMat). 26.2's GlProgram only assigns a binding
		// to uniform blocks the pipeline declares; an undeclared block is skipped with "Found
		// unknown and unsupported uniform", pass.setUniform() for it is silently dropped, and the
		// shader reads ModelViewMat from whatever buffer sits in that slot -- garbage vertex
		// positions, so no clouds (and the occasional stray face when a vanilla buffer happens to
		// be there). MATRICES_FOG_SNIPPET declares Globals, DynamicTransforms, Projection and
		// Fog, exactly as vanilla's RenderPipelines.CLOUDS does.
		this.pipeline = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
				.withLocation(CLOUDS_LOCATION)
				.withVertexShader(CLOUDS_VSH)
				.withFragmentShader(CLOUDS_FSH)
				.withBindGroupLayout(bgl)
				.withVertexBinding(0, CloudVertexFormat.QUAD_FORMAT)
				.withVertexBinding(1, CloudVertexFormat.INSTANCE_FORMAT)
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false)
				// 26.2 uses an inverted (reversed) depth buffer: the scene default
				// (GREATER_THAN_OR_EQUAL = "closer or equal", writeDepth=true) is what
				// every vanilla opaque pipeline declares. We draw AFTER the terrain into
				// the main target, so without an explicit state the pipeline defaults to
				// NULL depth state and GlCommandEncoder _disableDepthTest()s -- clouds
				// rendered through all terrain (Jan's 2026-09-12 screenshot).
				.withColorTargetState(new ColorTargetState(java.util.Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
				.withDepthStencilState(
						"1".equals(System.getenv("SIMPLECLOUDS_DEV")) && "1".equals(System.getenv("SIMPLECLOUDS_TEST_CLOUD_NO_DEPTH"))
								? new DepthStencilState(CompareOp.ALWAYS_PASS, false) : DepthStencilState.DEFAULT)
				.build();

		// Original weighted-blended transparency: accumulate premultiplied weighted
		// color in RGBA16F and multiply revealage in R8, without changing depth.
		BindGroupLayout transparencyBgl = BindGroupLayout.builder()
				.withUniform("CloudShading", UniformType.UNIFORM_BUFFER)
				.withUniform("CloudFog", UniformType.UNIFORM_BUFFER)
				.withUniform("CloudOffset", UniformType.UNIFORM_BUFFER)
				.withUniform("CloudClip", UniformType.UNIFORM_BUFFER)
				.withUniform("BayerMatrixSampler", UniformType.COMBINED_IMAGE_SAMPLER)
				.build();
		this.transparencyPipeline = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
				.withLocation(CLOUDS_TRANSPARENCY_LOCATION)
				.withVertexShader(CLOUDS_TRANSPARENCY_LOCATION)
				.withFragmentShader(CLOUDS_TRANSPARENCY_LOCATION)
				.withBindGroupLayout(transparencyBgl)
				.withVertexBinding(0, CloudVertexFormat.QUAD_FORMAT)
				.withVertexBinding(1, CloudVertexFormat.CUBE_INSTANCE_FORMAT)
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				// Original transparent cube pass keeps face culling enabled. The
				// rotated quad winding matches InstanceableMesh.defaultCube.
				.withCull(true)
				.withShaderDefine("ORIGINAL_ACCUM_ONLY")
				.withColorTargetState(new ColorTargetState(Optional.of(new BlendFunction(BlendFactor.ONE, BlendFactor.ONE)), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
				// GREATER_THAN_OR_EQUAL = the inverted-Z "closer or equal" test: LESS_THAN
				// (normal-Z) can never pass against a cleared (far=0.0) depth, which would
				// make every transparent face in open air invisible.
				.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
				.build();
		// RenderPearl currently rejects independent MRT blend functions. Two draws
		// preserve the original equations/formats/depth without raw GL state hacks.
		this.transparencyRevealagePipeline = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
				.withLocation(SimpleCloudsMod.id("core/original_revealage"))
				.withVertexShader(CLOUDS_TRANSPARENCY_LOCATION).withFragmentShader(CLOUDS_TRANSPARENCY_LOCATION)
				.withShaderDefine("ORIGINAL_REVEALAGE_ONLY").withBindGroupLayout(transparencyBgl)
				.withVertexBinding(0, CloudVertexFormat.QUAD_FORMAT).withVertexBinding(1, CloudVertexFormat.CUBE_INSTANCE_FORMAT)
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(true)
				.withColorTargetState(new ColorTargetState(Optional.of(new BlendFunction(BlendFactor.ZERO, BlendFactor.ONE_MINUS_SRC_COLOR)), GpuFormat.R8_UNORM, ColorTargetState.WRITE_ALL))
				.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false)).build();
		this.transparencyMrtPipeline = !IndexedCloudBlend.useIndexedBackend() ? null : RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
				.withLocation(SimpleCloudsMod.id("core/original_transparency_mrt"))
				.withVertexShader(CLOUDS_TRANSPARENCY_LOCATION).withFragmentShader(CLOUDS_TRANSPARENCY_LOCATION)
				.withBindGroupLayout(transparencyBgl)
				.withVertexBinding(0, CloudVertexFormat.QUAD_FORMAT).withVertexBinding(1, CloudVertexFormat.CUBE_INSTANCE_FORMAT)
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(true)
				// Backend scope supplies the original independent revealage blend.
				// Declared common ONE/ONE is restored before leaving this scope.
				.withColorTargetState(0,new ColorTargetState(Optional.of(new BlendFunction(BlendFactor.ONE, BlendFactor.ONE)), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
				.withColorTargetState(1,new ColorTargetState(Optional.of(new BlendFunction(BlendFactor.ONE, BlendFactor.ONE)), GpuFormat.R8_UNORM, ColorTargetState.WRITE_ALL))
				.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false)).build();

		// Storm fog overlay: fullscreen triangle, blended, no depth test (drawn last,
		// on top of everything in the scene).
		// Plan item 3: the spatial storm fog samples the scene depth (colour-only pass, like the
		// terrain shadows) and reconstructs world positions with the frame's matrices.
		BindGroupLayout stormFogBgl = BindGroupLayout.builder()
				.withUniform("OriginalStormFog", UniformType.UNIFORM_BUFFER)
				.withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
				.withUniform("ShadowMap", UniformType.COMBINED_IMAGE_SAMPLER)
				.withUniform("ShadowMapColor", UniformType.COMBINED_IMAGE_SAMPLER)
				.build();
		VertexFormat triangleFormat = VertexFormat.builder(0)
				.addAttribute(CloudVertexFormat.POSITION, GpuFormat.RG32_FLOAT)
				.build();
		Identifier transparencyCompositeId = SimpleCloudsMod.id("core/original_transparency_composite");
		this.transparencyCompositePipeline = RenderPipeline.builder()
				.withLocation(transparencyCompositeId).withVertexShader(STORM_FOG_LOCATION).withFragmentShader(transparencyCompositeId)
				.withBindGroupLayout(BindGroupLayout.builder()
						.withUniform("AccumTexture", UniformType.COMBINED_IMAGE_SAMPLER)
						.withUniform("RevealageTexture", UniformType.COMBINED_IMAGE_SAMPLER).build())
				.withVertexBinding(0, triangleFormat).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false).withDepthStencilState(Optional.empty())
				// avg * (1-revealage) + destination * revealage; retain scene alpha.
				.withColorTargetState(new ColorTargetState(new BlendFunction(BlendFactor.SRC_ALPHA,
						BlendFactor.ONE_MINUS_SRC_ALPHA, BlendFactor.ZERO, BlendFactor.ONE))).build();
		Identifier transitionId = SimpleCloudsMod.id("core/cloud_transition");
		Identifier previewCompositeId = SimpleCloudsMod.id("core/original_preview_composite");
		this.previewCompositePipeline = RenderPipeline.builder()
				.withLocation(previewCompositeId).withVertexShader(STORM_FOG_LOCATION).withFragmentShader(previewCompositeId)
				.withBindGroupLayout(BindGroupLayout.builder()
						.withUniform("CloudsTexture", UniformType.COMBINED_IMAGE_SAMPLER)
						.withUniform("AccumTexture", UniformType.COMBINED_IMAGE_SAMPLER)
						.withUniform("RevealageTexture", UniformType.COMBINED_IMAGE_SAMPLER).build())
				.withVertexBinding(0, triangleFormat).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false).withDepthStencilState(Optional.empty())
				.withColorTargetState(new ColorTargetState(new BlendFunction(BlendFactor.SRC_ALPHA,
						BlendFactor.ONE_MINUS_SRC_ALPHA, BlendFactor.ZERO, BlendFactor.ONE))).build();
		this.previewExportPipeline = RenderPipeline.builder()
				.withLocation(SimpleCloudsMod.id("core/original_preview_export"))
				.withVertexShader(STORM_FOG_LOCATION).withFragmentShader(previewCompositeId)
				.withBindGroupLayout(BindGroupLayout.builder()
						.withUniform("CloudsTexture", UniformType.COMBINED_IMAGE_SAMPLER)
						.withUniform("AccumTexture", UniformType.COMBINED_IMAGE_SAMPLER)
						.withUniform("RevealageTexture", UniformType.COMBINED_IMAGE_SAMPLER).build())
				.withVertexBinding(0, triangleFormat).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false).withDepthStencilState(Optional.empty())
				.withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA8_UNORM,ColorTargetState.WRITE_ALL)).build();
		this.transitionPipeline = RenderPipeline.builder()
				.withLocation(transitionId).withVertexShader(transitionId).withFragmentShader(transitionId)
				.withBindGroupLayout(BindGroupLayout.builder()
						.withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
						.withUniform("OldScene", UniformType.COMBINED_IMAGE_SAMPLER).withUniform("NewScene", UniformType.COMBINED_IMAGE_SAMPLER).build())
				.withVertexBinding(0, triangleFormat).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false).withDepthStencilState(Optional.empty())
				.withColorTargetState(new ColorTargetState(java.util.Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
				.build();
		this.stormFogPipeline = RenderPipeline.builder()
				.withLocation(STORM_FOG_LOCATION)
				.withVertexShader(STORM_FOG_LOCATION)
				.withFragmentShader(STORM_FOG_LOCATION)
				.withBindGroupLayout(stormFogBgl)
				.withVertexBinding(0, triangleFormat)
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false)
				.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
				.withDepthStencilState(Optional.empty())
				.build();
		Identifier stormCompositeId = SimpleCloudsMod.id("core/original_storm_composite");
		this.stormCompositePipeline = RenderPipeline.builder()
				.withLocation(stormCompositeId).withVertexShader(STORM_FOG_LOCATION).withFragmentShader(stormCompositeId)
				.withBindGroupLayout(BindGroupLayout.builder()
						.withUniform("StormFogSampler", UniformType.COMBINED_IMAGE_SAMPLER).build())
				.withVertexBinding(0, triangleFormat).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false).withDepthStencilState(Optional.empty())
				.withColorTargetState(new ColorTargetState(new BlendFunction(
						com.mojang.renderpearl.api.pipeline.BlendFactor.SRC_ALPHA,
						com.mojang.renderpearl.api.pipeline.BlendFactor.ONE_MINUS_SRC_ALPHA,
						com.mojang.renderpearl.api.pipeline.BlendFactor.ZERO,
						com.mojang.renderpearl.api.pipeline.BlendFactor.ONE))).build();
		Identifier dhDepthMergeId = SimpleCloudsMod.id("core/dh_depth_merge");
		// Built on the vanilla matrices snippet so the merge reads the Projection uniform the cloud
		// passes are drawn with (bob/hurt included), not a CPU-side copy of it.
		this.dhDepthMergePipeline = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
				.withLocation(dhDepthMergeId).withVertexShader(STORM_FOG_LOCATION).withFragmentShader(dhDepthMergeId)
				.withBindGroupLayout(BindGroupLayout.builder()
						.withUniform("DhDepthMerge", UniformType.UNIFORM_BUFFER)
						.withUniform("DhDepthSampler", UniformType.COMBINED_IMAGE_SAMPLER).build())
				.withVertexBinding(0, triangleFormat).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false)
				// Reversed-Z: keep whichever surface is closer, vanilla terrain or the LOD.
				.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN, true))
				.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL)).build();
		this.dhDepthMergeDebugPipeline = RenderPipeline.builder()
				.withLocation(SimpleCloudsMod.id("core/dh_depth_merge_debug")).withVertexShader(STORM_FOG_LOCATION).withFragmentShader(dhDepthMergeId)
				.withShaderDefine("DH_MERGE_DEBUG")
				.withBindGroupLayout(BindGroupLayout.builder()
						.withUniform("DhDepthMerge", UniformType.UNIFORM_BUFFER)
						.withUniform("DhDepthSampler", UniformType.COMBINED_IMAGE_SAMPLER).build())
				.withVertexBinding(0, triangleFormat).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false).withDepthStencilState(Optional.empty())
				.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL)).build();
		for (int i = 0; i < 3; i++) {
			final int slot = i;
			this.dhDepthMergeRing[i] = device.createBuffer(() -> "simpleclouds.dhDepthMerge" + slot,
					GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_READ, zeros(128));
		}
		this.stormUpscalePipeline = RenderPipeline.builder()
				.withLocation(SimpleCloudsMod.id("core/original_storm_upscale"))
				.withVertexShader(STORM_FOG_LOCATION).withFragmentShader(stormCompositeId)
				.withBindGroupLayout(BindGroupLayout.builder()
						.withUniform("StormFogSampler", UniformType.COMBINED_IMAGE_SAMPLER).build())
				.withVertexBinding(0, triangleFormat).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false).withDepthStencilState(Optional.empty())
				.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL)).build();
		Identifier stormBlurId = SimpleCloudsMod.id("core/original_storm_blur");
		Identifier worldFogId = SimpleCloudsMod.id("core/original_world_fog");
		this.worldFogPipeline = RenderPipeline.builder()
				.withLocation(worldFogId).withVertexShader(STORM_FOG_LOCATION).withFragmentShader(worldFogId)
				.withBindGroupLayout(BindGroupLayout.builder()
						.withUniform("OriginalWorldFog", UniformType.UNIFORM_BUFFER)
						.withUniform("DiffuseSampler", UniformType.COMBINED_IMAGE_SAMPLER)
						.withUniform("DiffuseDepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
						.withUniform("CloudDepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
						.withUniform("PreCloudDepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
						.withUniform("StormFogSampler", UniformType.COMBINED_IMAGE_SAMPLER).build())
				.withVertexBinding(0, triangleFormat).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false).withDepthStencilState(Optional.empty())
				.withColorTargetState(ColorTargetState.DEFAULT).build();
		Identifier dhFadeGuardId = SimpleCloudsMod.id("core/dh_fade_guard");
		this.dhFadeGuardPipeline = RenderPipeline.builder()
				.withLocation(dhFadeGuardId).withVertexShader(STORM_FOG_LOCATION).withFragmentShader(dhFadeGuardId)
				.withBindGroupLayout(BindGroupLayout.builder()
						.withUniform("SavedSampler", UniformType.COMBINED_IMAGE_SAMPLER)
						.withUniform("CloudDepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
						.withUniform("PreCloudDepthSampler", UniformType.COMBINED_IMAGE_SAMPLER).build())
				.withVertexBinding(0, triangleFormat).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false).withDepthStencilState(Optional.empty())
				.withColorTargetState(ColorTargetState.DEFAULT).build();
		for(int i=0;i<3;i++) {
			final int slot=i;
			this.worldFogRing[i]=device.createBuffer(() -> "simpleclouds.worldFog"+slot,
					GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_READ, zeros(OriginalWorldFogUniforms.BYTES));
		}
		this.stormBlurPipeline = RenderPipeline.builder()
				.withLocation(stormBlurId).withVertexShader(STORM_FOG_LOCATION).withFragmentShader(stormBlurId)
				.withBindGroupLayout(BindGroupLayout.builder()
						.withUniform("OriginalBlur", UniformType.UNIFORM_BUFFER)
						.withUniform("DiffuseSampler", UniformType.COMBINED_IMAGE_SAMPLER).build())
				.withVertexBinding(0, triangleFormat).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false).withDepthStencilState(Optional.empty())
				.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL)).build();

		ByteBuffer triangleData = ByteBuffer.allocateDirect(FULLSCREEN_TRIANGLE.length * 4).order(ByteOrder.nativeOrder());
		for (float v : FULLSCREEN_TRIANGLE)
			triangleData.putFloat(v);
		triangleData.flip();
		this.triangleBuffer = device.createBuffer(() -> "simpleclouds.fullscreenTriangle", GpuBuffer.USAGE_VERTEX, triangleData);

		for (int i=0;i<3;i++) {
			final int slot=i;
			this.stormFogRing[i] = device.createBuffer(() -> "simpleclouds.originalStormFog"+slot,
					GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_READ, zeros(OriginalStormFogUniforms.BYTES));
			this.lightningOcclusionRing[i] = device.createBuffer(() -> "simpleclouds.lightningOcclusion"+slot,
					GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_READ, zeros(LIGHTNING_OCCLUSION_BYTES));
		}
		this.stormLinearSampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
				FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.empty());
		for (int i=0;i<this.stormBlurRing.length;i++) {
			final int slot=i;
			this.stormBlurRing[i] = device.createBuffer(() -> "simpleclouds.originalStormBlur"+slot,
					GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_READ, zeros(16));
		}

		// Sky flash (storm plan step 1): same fullscreen-triangle shape as the
		// storm fog — a short white brightening of the whole screen (see
		// core/sky_flash.fsh for why the port draws this itself).
		BindGroupLayout skyFlashBgl = BindGroupLayout.builder()
				.withUniform("SkyFlash", UniformType.UNIFORM_BUFFER)
				.withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER) // plan item 3: sky pixels only
				.build();
		this.skyFlashPipeline = RenderPipeline.builder()
				.withLocation(SKY_FLASH_LOCATION)
				.withVertexShader(SKY_FLASH_LOCATION)
				.withFragmentShader(SKY_FLASH_LOCATION)
				.withBindGroupLayout(skyFlashBgl)
				.withVertexBinding(0, triangleFormat)
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false)
				.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
				.withDepthStencilState(Optional.empty())
				.build();
		this.skyFlashUbo = device.createBuffer(() -> "simpleclouds.skyFlash", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_READ, 16L);

		// ---- Cloud shadow map ----
		this.shadowTarget = new SimpleRenderTarget("simpleclouds.shadow", true, GpuFormat.RGBA8_UNORM);
		this.shadowTarget.createBuffers(SHADOW_SIZE, SHADOW_SIZE);
		this.stormShadowTarget = new SimpleRenderTarget("simpleclouds.originalStormShadow", true, GpuFormat.RGBA8_UNORM);
		this.stormShadowTarget.createBuffers(SHADOW_SIZE, SHADOW_SIZE);
		Identifier stormShadowId = SimpleCloudsMod.id("core/original_storm_shadow");
		this.stormShadowPipeline = RenderPipeline.builder()
				.withLocation(stormShadowId).withVertexShader(stormShadowId).withFragmentShader(stormShadowId)
				.withBindGroupLayout(BindGroupLayout.builder()
						.withUniform("ShadowMatrices", UniformType.UNIFORM_BUFFER)
						.withUniform("StormShadow", UniformType.UNIFORM_BUFFER).build())
				.withVertexBinding(0, CloudVertexFormat.QUAD_FORMAT)
				.withVertexBinding(1, CloudVertexFormat.INSTANCE_FORMAT)
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
				.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
				.withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN, true)).build();

		BindGroupLayout shadowBgl = BindGroupLayout.builder()
				.withUniform("ShadowMatrices", UniformType.UNIFORM_BUFFER)
				.withUniform("CloudClip", UniformType.UNIFORM_BUFFER)
				.build();
		this.shadowPipeline = RenderPipeline.builder()
				.withLocation(CLOUDS_SHADOW_LOCATION)
				.withVertexShader(CLOUDS_SHADOW_LOCATION)
				.withFragmentShader(CLOUDS_SHADOW_LOCATION)
				.withBindGroupLayout(shadowBgl)
				.withVertexBinding(0, CloudVertexFormat.QUAD_FORMAT)
				.withVertexBinding(1, CloudVertexFormat.INSTANCE_FORMAT)
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false)
				.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, 0))
				// Standard-Z ortho (joml setOrtho): the light sits at window depth ~0.0 and
				// the map clears to 1.0, so the cloud CLOSEST to the light wins with
				// LESS_THAN (the scene/inverted-Z default would reject every fragment).
				.withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN, true))
				.build();

		BindGroupLayout terrainBgl = BindGroupLayout.builder()
				.withUniform("ShadowPass", UniformType.UNIFORM_BUFFER)
				.withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
				.withUniform("DiffuseSampler", UniformType.COMBINED_IMAGE_SAMPLER)
				.withUniform("ShadowMap", UniformType.COMBINED_IMAGE_SAMPLER)
				.build();
		this.terrainPipeline = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
				.withLocation(TERRAIN_SHADOWS_LOCATION)
				.withVertexShader(TERRAIN_SHADOWS_LOCATION)
				.withFragmentShader(TERRAIN_SHADOWS_LOCATION)
				.withBindGroupLayout(terrainBgl)
				.withVertexBinding(0, triangleFormat)
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false)
				.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
				.withDepthStencilState(Optional.empty())
				.build();

		BindGroupLayout atmosphericBgl = BindGroupLayout.builder()
				.withUniform("AtmosphericPass", UniformType.UNIFORM_BUFFER)
				.withUniform("DiffuseSampler", UniformType.COMBINED_IMAGE_SAMPLER)
				.build();
		// The shader composites a separate scene copy; write its complete result.
		this.atmosphericPipeline = RenderPipeline.builder()
				.withLocation(ATMOSPHERIC_CLOUDS_LOCATION)
				.withVertexShader(ATMOSPHERIC_CLOUDS_LOCATION)
				.withFragmentShader(ATMOSPHERIC_CLOUDS_LOCATION)
				.withBindGroupLayout(atmosphericBgl)
				.withVertexBinding(0, triangleFormat)
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false)
				.withColorTargetState(ColorTargetState.DEFAULT)
				.withDepthStencilState(Optional.empty())
				.build();

		this.lightningPipeline = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
				.withLocation(LIGHTNING_LOCATION)
				.withVertexShader(LIGHTNING_LOCATION)
				.withFragmentShader(LIGHTNING_LOCATION)
				.withBindGroupLayout(BindGroupLayout.builder()
						.withUniform("LightningOcclusion", UniformType.UNIFORM_BUFFER)
						.withUniform("DhDepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
						.build())
				.withVertexBinding(0, LIGHTNING_FORMAT)
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false)
				.withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
				.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
				.build();
		// 24 verts/section, 6 quads -> 12 tris; the section topology is fixed, so
		// build the indices once for a full 4096-vertex capacity window and slice.
		int lightningSections = 4096 / 24; // 170 full 24-vertex sections fit in 4096 vertices
		short[] lightningIndices = new short[lightningSections * 36];
		for (int sec = 0; sec < lightningSections; sec++)
		{
			int base = sec * 24;
			int o = sec * 36;
			for (int face = 0; face < 6; face++)
			{
				int v0 = base + face * 4;
				lightningIndices[o++] = (short) v0;
				lightningIndices[o++] = (short) (v0 + 1);
				lightningIndices[o++] = (short) (v0 + 2);
				lightningIndices[o++] = (short) (v0 + 2);
				lightningIndices[o++] = (short) (v0 + 3);
				lightningIndices[o++] = (short) v0;
			}
		}
		java.nio.ByteBuffer lightningIndexData = java.nio.ByteBuffer.allocateDirect(lightningIndices.length * 2).order(java.nio.ByteOrder.nativeOrder());
		for (short sh : lightningIndices)
			lightningIndexData.putShort(sh);
		lightningIndexData.flip();
		this.lightningIndexBuffer = device.createBuffer(() -> "simpleclouds.lightningIndices", GpuBuffer.USAGE_INDEX, lightningIndexData);

		// std140: two mat4 blocks = 64 bytes each = 128 bytes total.
		// maxAnisotropy is validated as 1..16 by GpuDevice.createSampler.
		this.nearestSampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());

		// Base quad vertex buffer.
		ByteBuffer quadData = ByteBuffer.allocateDirect(QUAD_VERTICES.length * 4).order(ByteOrder.nativeOrder());
		for (float v : QUAD_VERTICES)
			quadData.putFloat(v);
		quadData.flip();
		this.quadVertexBuffer = device.createBuffer(() -> "simpleclouds.quad", GpuBuffer.USAGE_VERTEX, quadData);

		// Base quad index buffer (ushort).
		ByteBuffer indexData = ByteBuffer.allocateDirect(QUAD_INDICES.length * 2).order(ByteOrder.nativeOrder());
		for (int i : QUAD_INDICES)
			indexData.putShort((short) i);
		indexData.flip();
		this.quadIndexBuffer = device.createBuffer(() -> "simpleclouds.quadIndex", GpuBuffer.USAGE_INDEX, indexData);
		ByteBuffer cubeData = ByteBuffer.allocateDirect(CUBE_VERTICES.length * 4).order(ByteOrder.nativeOrder());
		for (float v : CUBE_VERTICES) cubeData.putFloat(v);
		cubeData.flip();
		this.cubeVertexBuffer = device.createBuffer(() -> "simpleclouds.originalCube", GpuBuffer.USAGE_VERTEX, cubeData);
		ByteBuffer cubeIndices = ByteBuffer.allocateDirect(CUBE_INDICES.length * 2).order(ByteOrder.nativeOrder());
		for (int i : CUBE_INDICES) cubeIndices.putShort((short)i);
		cubeIndices.flip();
		this.cubeIndexBuffer = device.createBuffer(() -> "simpleclouds.originalCubeIndex", GpuBuffer.USAGE_INDEX, cubeIndices);

		// UBOs (std140 layout).
		int uboUsage = GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_READ;
		this.lightingUbo = device.createBuffer(() -> "simpleclouds.lighting", uboUsage, 40L);
		this.shadingUbo = device.createBuffer(() -> "simpleclouds.shading", uboUsage, 20L);
		this.fogUbo = device.createBuffer(() -> "simpleclouds.fog", uboUsage, 28L);
		for (int i = 0; i < 3; i++)
		{
			final int slot = i;
			// Usage exactly like the working static UBOs (UNIFORM|MAP_READ): with
			// MAP_WRITE set, the immediate glMapBuffer in GlBuffer$Direct fails on
			// this Mesa/Intel ARL machine ("Failed to map buffer").
			this.shadowMatricesRing[slot] = device.createBuffer(() -> "simpleclouds.shadowMatrices" + slot, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_READ, zeros(128));
			this.stormShadowMatricesRing[slot] = device.createBuffer(() -> "simpleclouds.originalStormMatrices" + slot, uboUsage, zeros(128));
			this.stormShadowParamsRing[slot] = device.createBuffer(() -> "simpleclouds.originalStormParams" + slot, uboUsage, zeros(16));
			this.terrainPassRing[slot] = device.createBuffer(() -> "simpleclouds.terrainShadowPass" + slot, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_READ, zeros(112));
			// std140: inverse matrices at 0/64, mat2 columns at 128/144,
			// scalars at 160..188, vec4 at 192 (208 bytes total).
			this.atmosphericRing[slot] = device.createBuffer(() -> "simpleclouds.atmospheric" + slot, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_READ, zeros(OriginalAtmosphericUniforms.BYTES));
		}
		for (int mode = 0; mode < this.shadingModes.length; mode++)
		{
			final int selectedMode = mode;
			ByteBuffer shading = zeros(16);
			shading.putFloat(0, CLOUD_SHADING_BASE[0]).putFloat(4, CLOUD_SHADING_BASE[1])
					.putFloat(8, CLOUD_SHADING_BASE[2]).putFloat(12, (float)mode);
			this.shadingModes[mode] = device.createBuffer(() -> "simpleclouds.shadingMode" + selectedMode,
					GpuBuffer.USAGE_UNIFORM, shading);
		}
		for (int i = 0; i < 3; i++)
		{
			final int slot = i;
			this.offsetRing[slot] = device.createBuffer(() -> "simpleclouds.offset" + slot, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_READ, zeros(16));
		}
		this.offsetZero = device.createBuffer(() -> "simpleclouds.offsetZero", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_READ, zeros(16));
		this.clipDisabled = device.createBuffer(() -> "simpleclouds.clipDisabled", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_READ, zeros(16));
		this.writeLighting(0.2F, 1.0F, -0.7F, -0.2F, 1.0F, 0.7F, 0.4F, 0.9F);
		this.writeShading(0.0F, 0.0F, 0.15F, 1.0F);
		// Fog in world-block units: clouds start ~100 blocks from the camera. The fog
		// color should track the sky (deferred — currently a fixed bright value).
		this.writeFog(0.85F, 0.85F, 0.95F, 1.0F, 100.0F, 400.0F, 0.05F);
	}

	private void writeLighting(float l0x, float l0y, float l0z, float l1x, float l1y, float l1z, float power, float ambient)
	{
		try (var view = this.lightingUbo.slice().map(true, false))
		{
			ByteBuffer data = view.data();
			data.putFloat(0, l0x);
			data.putFloat(4, l0y);
			data.putFloat(8, l0z);
			data.putFloat(16, l1x);
			data.putFloat(20, l1y);
			data.putFloat(24, l1z);
			data.putFloat(32, power);
			data.putFloat(36, ambient);
		}
	}

	private void writeShading(float dx, float dy, float dz, float useNormals)
	{
		try (var view = this.shadingUbo.slice().map(true, false))
		{
			ByteBuffer data = view.data();
			data.putFloat(0, dx);
			data.putFloat(4, dy);
			data.putFloat(8, dz);
			data.putFloat(16, useNormals);
		}
	}

	private void writeFog(float fr, float fg, float fb, float fa, float fogStart, float fogEnd, float ditherScale)
	{
		try (var view = this.fogUbo.slice().map(true, false))
		{
			ByteBuffer data = view.data();
			data.putFloat(0, fr);
			data.putFloat(4, fg);
			data.putFloat(8, fb);
			data.putFloat(12, fa);
			data.putFloat(16, fogStart);
			data.putFloat(20, fogEnd);
			data.putFloat(24, ditherScale);
		}
	}

	/**
	 * Updates the cloud fog (color + range) for the current frame (VISUAL-PARITY-PLAN
	 * step 3). The range is in world-block units (matching the shader's
	 * fogDistance = view-space length), and the color should be the vanilla sky/fog
	 * color so distant clouds blend into the sky.
	 */
	public void setFog(float r, float g, float b, float fogStart, float fogEnd)
	{
		this.writeFog(r, g, b, 1.0F, fogStart, fogEnd, 0.05F);
	}

	private boolean loggedFirstDraw = false;

	/**
	 * Draws the current cloud faces into the main render target.
	 * @param viewMatrix the world view matrix (R * T(-cameraPos)) — the ModelViewMat for a
	 *                   world at the origin; the model-view stack is already popped by the
	 *                   time our LevelRenderer.render TAIL hook runs.
	 */

	/** Draws an explicit instance buffer with a global fade alpha
	 *  (ColorModulator.a; the original per-chunk fade-in, step 3). */
	/**
	 * Draws one cloud chunk. Step 5: the chunk mesh was generated at a snapshot of
	 * the scroll drift (genScroll), so it is drawn shifted by (scroll - genScroll)
	 * — the field follows the drift continuously instead of jumping in
	 * SCROLL_REGEN_THRESHOLD steps, and adjacent chunks at different generation
	 * phases line up at their border (both show the exact field value at each
	 * world position).
	 */
	public void drawClouds(Matrix4f viewMatrix, GpuBuffer instances, int count, float alpha,
			float offX, float offY, float offZ)
	{
		this.drawClouds(viewMatrix, instances, count, alpha, offX, offY, offZ, null);
	}

	public void drawClouds(Matrix4f viewMatrix, GpuBuffer instances, int count, float alpha,
			float offX, float offY, float offZ, CloudWorldCoverage.Rect clip)
	{
		if (instances == null || count == 0)
			return;
		if (!this.loggedFirstDraw)
		{
			this.loggedFirstDraw = true;
			LOGGER.info("Simple Clouds clouds: first draw, {} instances", count);
		}

		RenderTarget main = this.cloudDestination != null ? this.cloudDestination : Minecraft.getInstance().gameRenderer.mainRenderTarget();
		GpuTextureView colorView = main.getColorTextureView();
		GpuTextureView depthView = main.getDepthTextureView();

		// DynamicTransforms is not covered by bindDefaultUniforms, so bind it explicitly as
		// vanilla CloudRenderer does. Written into vanilla's shared per-frame ring buffer (reset
		// every frame) -- allocating a fresh DynamicUniforms per draw created and freed GPU
		// buffers every frame.
// ColorModulator.a carries the fade alpha (the 1.20.1 chunk fade-in, step 3):
		Matrix4f shiftedView = new Matrix4f(viewMatrix).translate(offX, offY, offZ);
		GpuBufferSlice transforms = this.frameTransform(shiftedView, this.cloudModulator(alpha));
		GpuBufferSlice cellClip = this.cellClip(clip);
		if (transforms == null || cellClip == null)
			return;

		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		var compiledPipeline = RenderSystem.getCompiledPipeline(this.pipeline);
		AbstractTexture bayer = Minecraft.getInstance().getTextureManager().getTexture(BAYER_TEXTURE);
		RenderPass pass = encoder.createRenderPass(() -> "simpleclouds", colorView, Optional.empty(), depthView, OptionalDouble.empty());
		pass.setPipeline(compiledPipeline);
		RenderSystem.bindDefaultUniforms(pass);
		// Dither matrix: TextureManager.getTexture auto-loads simpleclouds:textures/shader/bayer_matrix.png
		// as a SimpleTexture (same resource the 1.20.1 mod sampled). Declared on the bind group layout
		// AND bound here -- rule 3: an unbound/undeclared sampler is a hard failure.
		// (hoisted above the render pass: 26.3 forbids texture uploads inside one)
		pass.setUniform("BayerMatrixSampler", bayer.getTextureView(), bayer.getSampler());
		pass.setUniform("DynamicTransforms", transforms);
		pass.setUniform("CloudLighting", this.lightingUbo);
		// Same sixteen bytes as before, without mapping/uploading for every chunk.
		pass.setUniform("CloudShading", this.shadingModes[SimpleCloudsConfig.CLIENT.cubeNormals.get() ? 1 : 0]);
		pass.setUniform("CloudFog", this.previewActive ? this.previewFog : this.fogUbo);
		if (this.previewActive) pass.setUniform("Projection", this.previewProjection);
		// Per-draw transform slices retain their data until the frame completes.
		// Rewriting a three-buffer offset ring hundreds of times in one frame
		// either stalls the GPU or lets queued draws observe a later chunk's offset.
		pass.setUniform("CloudOffset", this.offsetZero);
		pass.setUniform("CloudClip", cellClip);
		pass.setVertexBuffer(0, this.quadVertexBuffer.slice());
		pass.setVertexBuffer(1, instances.slice());
		pass.setIndexBuffer(this.quadIndexBuffer, IndexType.SHORT);
		pass.drawIndexed(QUAD_INDICES.length, count, 0, 0, 0);
		pass.close();
	}

	/** Original 200-step GPU shadow-volume raymarch, at original quarter resolution. */
	public void drawStormFog(Matrix4f viewMatrix, Matrix4f projection, double camX, double camY, double camZ,
			float fogEnd, float[] color, float[] bolts, int boltCount, int debugMode)
	{
		if (!this.stormShadowRendered || projection == null) return;
		this.stormFogSlot = (this.stormFogSlot + 1) % 3;
		GpuBuffer params = this.stormFogRing[this.stormFogSlot];
		try (var view = params.slice().map(true, false))
		{
			OriginalStormFogUniforms.write(view.data(), projection, viewMatrix,
					this.stormShadowProjection, this.stormShadowView, camX, camY, camZ,
					fogEnd, color, bolts, boltCount, debugMode);
		}
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		int width=Math.max(1,main.width/4), height=Math.max(1,main.height/4);
		if (this.originalStormFogTarget == null || this.originalStormFogTarget.width != width || this.originalStormFogTarget.height != height)
		{
			if (this.originalStormFogTarget != null) this.originalStormFogTarget.destroyBuffers();
			this.originalStormFogTarget = new com.mojang.blaze3d.pipeline.TextureTarget("simpleclouds.originalStormFog",
					width,height,GpuFormat.RGBA8_UNORM,null);
		}
		if (this.stormBlurMain == null || this.stormBlurMain.width != main.width || this.stormBlurMain.height != main.height)
		{
			if (this.stormBlurMain != null) this.stormBlurMain.destroyBuffers();
			if (this.stormBlurSwap != null) this.stormBlurSwap.destroyBuffers();
			this.stormBlurMain = new com.mojang.blaze3d.pipeline.TextureTarget("simpleclouds.originalBlurMain",main.width,main.height,GpuFormat.RGBA8_UNORM,null);
			this.stormBlurSwap = new com.mojang.blaze3d.pipeline.TextureTarget("simpleclouds.originalBlurSwap",main.width,main.height,GpuFormat.RGBA8_UNORM,null);
		}
		// Upload six distinct slices before any render pass; no per-pass mapping alias.
		for (int i=0;i<6;i++)
			try (var mapping=this.stormBlurRing[this.stormFogSlot*6+i].slice().map(true,false))
			{
				mapping.data().putFloat(0,i%2==0?1.0F/main.width:0).putFloat(4,i%2==1?1.0F/main.height:0)
						.putFloat(8,10.0F).putFloat(12,0);
			}
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		var compiledStormFogPipeline = RenderSystem.getCompiledPipeline(this.stormFogPipeline);
		var compiledCompositePipeline = RenderSystem.getCompiledPipeline(this.stormCompositePipeline);
		var compiledUpscalePipeline = RenderSystem.getCompiledPipeline(this.stormUpscalePipeline);
		var compiledBlurPipeline = RenderSystem.getCompiledPipeline(this.stormBlurPipeline);
		try (RenderPass pass = encoder.createRenderPass(() -> "simpleclouds.originalStormFog",
				this.originalStormFogTarget.getColorTextureView(), Optional.of(new org.joml.Vector4f())))
		{
			pass.setPipeline(compiledStormFogPipeline);
			pass.setUniform("OriginalStormFog", params);
			pass.setUniform("DepthSampler", main.getDepthTextureView(), this.nearestSampler);
			pass.setUniform("ShadowMap", this.stormShadowTarget.getDepthTextureView(), this.stormLinearSampler);
			pass.setUniform("ShadowMapColor", this.stormShadowTarget.getColorTextureView(), this.nearestSampler);
			pass.setVertexBuffer(0, this.triangleBuffer.slice()); pass.draw(3,1,0,0);
		}
		// Original: alpha-preserving linear upscale, then six alternating full-size blur passes.
		try (RenderPass pass = encoder.createRenderPass(() -> "simpleclouds.originalStormUpscale", this.stormBlurMain.getColorTextureView(), Optional.empty()))
		{
			pass.setPipeline(compiledUpscalePipeline);
			pass.setUniform("StormFogSampler", this.originalStormFogTarget.getColorTextureView(), this.stormLinearSampler);
			pass.setVertexBuffer(0,this.triangleBuffer.slice()); pass.draw(3,1,0,0);
		}
		for (int i=0;i<6;i++)
		{
			RenderTarget source=i%2==0?this.stormBlurMain:this.stormBlurSwap;
			RenderTarget target=i%2==0?this.stormBlurSwap:this.stormBlurMain;
			try (RenderPass pass=encoder.createRenderPass(() -> "simpleclouds.originalStormBlur",target.getColorTextureView(),Optional.empty()))
			{
				pass.setPipeline(compiledBlurPipeline);
				pass.setUniform("OriginalBlur",this.stormBlurRing[this.stormFogSlot*6+i]);
				pass.setUniform("DiffuseSampler",source.getColorTextureView(),this.stormLinearSampler);
				pass.setVertexBuffer(0,this.triangleBuffer.slice()); pass.draw(3,1,0,0);
			}
		}
		try (RenderPass pass = encoder.createRenderPass(() -> "simpleclouds.originalStormComposite", main.getColorTextureView(), Optional.empty()))
		{
			pass.setPipeline(compiledCompositePipeline);
			pass.setUniform("StormFogSampler", this.stormBlurMain.getColorTextureView(), this.stormLinearSampler);
			pass.setVertexBuffer(0, this.triangleBuffer.slice()); pass.draw(3,1,0,0);
		}
		this.stormFogReady = true;
	}

	/** Save cloud-only depth before terrain writes to the main depth attachment. */
	/**
	 * Writes DH's LOD depth, re-projected with Minecraft's projection, into the main depth so
	 * the late cloud, fog and weather passes are hidden behind DH terrain. The previous depth
	 * is kept for {@link #restoreMainDepth()}. Returns false when nothing was changed.
	 */
	public boolean mergeDhDepth(GpuTextureView dhDepth, Matrix4f inverseDhProjection, Matrix4f projection)
	{
		if (dhDepth == null || inverseDhProjection == null || projection == null
				|| !inverseDhProjection.isFinite() || !projection.isFinite()) return false;
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		if (this.dhDepthScratch == null || this.dhDepthScratch.width != main.width || this.dhDepthScratch.height != main.height)
		{
			if (this.dhDepthScratch != null) this.dhDepthScratch.destroyBuffers();
			if (this.dhDepthBackup != null) this.dhDepthBackup.destroyBuffers();
			this.dhDepthScratch = new com.mojang.blaze3d.pipeline.TextureTarget("simpleclouds.dhDepthScratch",
					main.width, main.height, GpuFormat.RGBA8_UNORM, null);
			this.dhDepthBackup = new com.mojang.blaze3d.pipeline.TextureTarget("simpleclouds.dhDepthBackup",
					main.width, main.height, main.getColorTexture().getFormat(), main.getDepthTexture().getFormat());
		}
		this.dhDepthBackup.copyDepthFrom(main);
		this.dhDepthMergeSlot = (this.dhDepthMergeSlot + 1) % 3;
		GpuBuffer params = this.dhDepthMergeRing[this.dhDepthMergeSlot];
		try (var mapping = params.slice().map(true, false))
		{
			inverseDhProjection.get(0, mapping.data());
			projection.get(64, mapping.data());
		}
		if ("1".equals(System.getenv("SIMPLECLOUDS_DEV")) && "1".equals(System.getenv("SIMPLECLOUDS_TEST_DH_MERGE_DEBUG")))
		{
			CommandEncoder debugEncoder = RenderSystem.getDevice().createCommandEncoder();
			try (RenderPass pass = debugEncoder.createRenderPass(() -> "simpleclouds.dhDepthMergeDebug", main.getColorTextureView(), Optional.empty()))
			{
				pass.setPipeline(RenderSystem.getCompiledPipeline(this.dhDepthMergeDebugPipeline));
				pass.setUniform("DhDepthMerge", params);
				pass.setUniform("DhDepthSampler", dhDepth, this.nearestSampler);
				pass.setVertexBuffer(0, this.triangleBuffer.slice());
				pass.draw(3, 1, 0, 0);
			}
			return false;
		}
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		var compiled = RenderSystem.getCompiledPipeline(this.dhDepthMergePipeline);
		try (RenderPass pass = encoder.createRenderPass(() -> "simpleclouds.dhDepthMerge",
				this.dhDepthScratch.getColorTextureView(), Optional.empty(), main.getDepthTextureView(), OptionalDouble.empty()))
		{
			pass.setPipeline(compiled);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("DhDepthMerge", params);
			pass.setUniform("DhDepthSampler", dhDepth, this.nearestSampler);
			pass.setVertexBuffer(0, this.triangleBuffer.slice());
			pass.draw(3, 1, 0, 0);
		}
		return true;
	}

	/** Puts back the main depth saved by {@link #mergeDhDepth}. */
	public void restoreMainDepth()
	{
		if (this.dhDepthBackup == null) return;
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		if (this.dhDepthBackup.width == main.width && this.dhDepthBackup.height == main.height)
			main.copyDepthFrom(this.dhDepthBackup);
	}

	/**
	 * Depth just before the clouds are drawn. The world fog treats a pixel as cloud only where
	 * the clouds changed the depth: with Distant Horizons the clouds are drawn after the solid
	 * terrain, so the post-cloud snapshot alone also holds terrain and DH depth.
	 */
	public void capturePreCloudDepth()
	{
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		if(this.preCloudDepthSnapshot==null || this.preCloudDepthSnapshot.width!=main.width || this.preCloudDepthSnapshot.height!=main.height) {
			var replacement = new com.mojang.blaze3d.pipeline.TextureTarget("simpleclouds.preCloudDepthSnapshot",
					main.width,main.height,main.getColorTexture().getFormat(),main.getDepthTexture().getFormat());
			if(this.preCloudDepthSnapshot!=null) this.preCloudDepthSnapshot.destroyBuffers();
			this.preCloudDepthSnapshot=replacement;
		}
		this.preCloudDepthSnapshot.copyDepthFrom(main);
	}

	/** Before DH's vanilla fade (executeOutline): keep the colour so cloud pixels can be restored. */
	public void beginDhFadeGuard()
	{
		this.dhFadeGuardArmed = false;
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		if (!this.cloudDepthReady || this.preCloudDepthSnapshot == null || this.cloudDepthSnapshot == null
				|| this.preCloudDepthSnapshot.width != main.width || this.preCloudDepthSnapshot.height != main.height
				|| this.cloudDepthSnapshot.width != main.width || this.cloudDepthSnapshot.height != main.height) return;
		if (this.dhFadeGuardSaved == null || this.dhFadeGuardSaved.width != main.width || this.dhFadeGuardSaved.height != main.height)
		{
			if (this.dhFadeGuardSaved != null) this.dhFadeGuardSaved.destroyBuffers();
			this.dhFadeGuardSaved = new com.mojang.blaze3d.pipeline.TextureTarget("simpleclouds.dhFadeGuard",
					main.width, main.height, main.getColorTexture().getFormat(), null);
		}
		RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(main.getColorTexture(),
				this.dhFadeGuardSaved.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
		this.dhFadeGuardArmed = true;
	}

	/** After DH's vanilla fade: put the saved colour back where the cloud pass drew. */
	public void endDhFadeGuard()
	{
		if (!this.dhFadeGuardArmed) return;
		this.dhFadeGuardArmed = false;
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		if (this.dhFadeGuardSaved.width != main.width || this.dhFadeGuardSaved.height != main.height) return;
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		try (RenderPass pass = encoder.createRenderPass(() -> "simpleclouds.dhFadeGuard", main.getColorTextureView(), Optional.empty()))
		{
			pass.setPipeline(RenderSystem.getCompiledPipeline(this.dhFadeGuardPipeline));
			pass.setUniform("SavedSampler", this.dhFadeGuardSaved.getColorTextureView(), this.nearestSampler);
			pass.setUniform("CloudDepthSampler", this.cloudDepthSnapshot.getDepthTextureView(), this.nearestSampler);
			pass.setUniform("PreCloudDepthSampler", this.preCloudDepthSnapshot.getDepthTextureView(), this.nearestSampler);
			pass.setVertexBuffer(0, this.triangleBuffer.slice());
			pass.draw(3, 1, 0, 0);
		}
	}

	public void captureCloudDepth()
	{
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		if(this.cloudDepthSnapshot==null || this.cloudDepthSnapshot.width!=main.width || this.cloudDepthSnapshot.height!=main.height) {
			var replacement = new com.mojang.blaze3d.pipeline.TextureTarget("simpleclouds.cloudDepthSnapshot",
					main.width,main.height,main.getColorTexture().getFormat(),main.getDepthTexture().getFormat());
			if(this.cloudDepthSnapshot!=null) this.cloudDepthSnapshot.destroyBuffers();
			this.cloudDepthSnapshot=replacement;
		}
		this.cloudDepthSnapshot.copyDepthFrom(main);
		this.cloudDepthReady=true;
	}

	/** Original post-fog, executed after terrain and before the mod's weather redraw. */
	public boolean drawWorldFog(Matrix4f projection, Matrix4f viewRotation, float start, float end, float[] color)
	{
		RenderTarget main=Minecraft.getInstance().gameRenderer.mainRenderTarget();
		if(!this.cloudDepthReady || this.cloudDepthSnapshot.width!=main.width || this.cloudDepthSnapshot.height!=main.height
				|| !projection.isFinite() || projection.determinant()==0 || !viewRotation.isFinite()) return false;
		this.worldFogSlot=(this.worldFogSlot+1)%3;
		GpuBuffer params=this.worldFogRing[this.worldFogSlot];
		try(var mapping=params.slice().map(true,false)) {
			OriginalWorldFogUniforms.write(mapping.data(),projection,viewRotation,start,end,color,this.stormFogReady);
		}
		if(this.worldFogSource==null || this.worldFogSource.width!=main.width || this.worldFogSource.height!=main.height) {
			var replacement=new com.mojang.blaze3d.pipeline.TextureTarget("simpleclouds.worldFogSource",main.width,main.height,main.getColorTexture().getFormat(),null);
			if(this.worldFogSource!=null) this.worldFogSource.destroyBuffers();
			this.worldFogSource=replacement;
		}
		CommandEncoder encoder=RenderSystem.getDevice().createCommandEncoder();
		encoder.copyTextureToTexture(main.getColorTexture(),this.worldFogSource.getColorTexture(),0,0,0,0,0,main.width,main.height);
		var compiled=RenderSystem.getCompiledPipeline(this.worldFogPipeline);
		try(RenderPass pass=encoder.createRenderPass(() -> "simpleclouds.originalWorldFog",main.getColorTextureView(),Optional.empty())) {
			pass.setPipeline(compiled); pass.setUniform("OriginalWorldFog",params);
			pass.setUniform("DiffuseSampler",this.worldFogSource.getColorTextureView(),this.nearestSampler);
			pass.setUniform("DiffuseDepthSampler",main.getDepthTextureView(),this.nearestSampler);
			pass.setUniform("CloudDepthSampler",this.cloudDepthSnapshot.getDepthTextureView(),this.nearestSampler);
			var pre = this.preCloudDepthSnapshot != null && this.preCloudDepthSnapshot.width == main.width
					&& this.preCloudDepthSnapshot.height == main.height ? this.preCloudDepthSnapshot : this.cloudDepthSnapshot;
			pass.setUniform("PreCloudDepthSampler",pre.getDepthTextureView(),this.nearestSampler);
			pass.setUniform("StormFogSampler",this.stormFogReady?this.stormBlurMain.getColorTextureView():this.worldFogSource.getColorTextureView(),this.stormLinearSampler);
			pass.setVertexBuffer(0,this.triangleBuffer.slice()); pass.draw(3,1,0,0);
		}
		if((!this.worldFogLogged || (this.stormFogReady && !this.worldFogStormLogged)) && "1".equals(System.getenv("SIMPLECLOUDS_DEV"))) {
			this.worldFogLogged=true;
			if(this.stormFogReady) this.worldFogStormLogged=true;
			LOGGER.info("[WORLD-FOG] executed {}x{} start={} end={} storm={} reversedZ=true",main.width,main.height,start,end,this.stormFogReady);
		}
		if (++this.worldFogDraws > 1)
			LOGGER.error("Simple Clouds ERROR: world fog executed more than once in the current frame");
		return true;
	}

	/**
	 * Draws the sky flash (storm plan step 1): a short full-screen white
	 * brightening while a nearby (<= 2000 blocks) bolt is bright. The strength is
	 * the gated flash from WorldEffects.flashStrength (2-tick vanilla sky-flash
	 * renewal + "Hide Sky Flashes" option); 0 means the pass is skipped.
	 */
	public void drawSkyFlash(float strength)
	{
		if (strength <= 0.0F)
			return;
		try (var view = this.skyFlashUbo.slice().map(true, false))
		{
			view.data().putFloat(0, strength);
		}
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		GpuTextureView colorView = main.getColorTextureView();
		GpuTextureView depthView = main.getDepthTextureView();

		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		// Plan item 3: colour-only pass that samples the depth, so only sky pixels brighten.
		var compiledSkyFlashPipeline = RenderSystem.getCompiledPipeline(this.skyFlashPipeline);
		RenderPass pass = encoder.createRenderPass(() -> "simpleclouds.skyFlash", colorView, Optional.empty());
		pass.setPipeline(compiledSkyFlashPipeline);
		pass.setUniform("SkyFlash", this.skyFlashUbo);
		pass.setUniform("DepthSampler", depthView, this.nearestSampler);
		pass.setVertexBuffer(0, this.triangleBuffer.slice());
		pass.draw(3, 1, 0, 0);
		pass.close();
	}

	/**
	 * Lets vanilla draw its rain and snow AFTER the clouds, in a pass of ours on the main target.
	 *
	 * <p>Vanilla's weather slot runs before the cloud pass. Weather is translucent and writes no
	 * depth, so the mod's opaque clouds - drawn later, at the level render tail - simply painted
	 * over it: rain was visible on the ground and in the gaps between clouds and stopped dead at
	 * every cloud edge. Cancelling vanilla's slot ({@code MixinWeatherEffectRenderer}) and calling
	 * its own renderer here puts the rain back on top, using vanilla's code, textures, lighting
	 * and snow handling rather than a second implementation of them.
	 */
	private boolean loggedWeatherState = false;

	public void drawVanillaWeather(net.minecraft.client.renderer.WeatherEffectRenderer weather,
			net.minecraft.client.renderer.state.level.WeatherRenderState state,
			net.minecraft.world.phys.Vec3 cameraPos, Matrix4f viewMatrix)
	{
		// Uploads the instance buffer for those columns - device work, so it has to happen
		// before a render pass is open (26.3 refuses any command while one is).
		weather.prepare(cameraPos, state);
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		var modelView = RenderSystem.getModelViewStack();
		modelView.pushMatrix();
		// prepare() already subtracts camera position. Vanilla render() binds its
		// own DynamicTransforms from this stack, overriding caller-bound slices.
		modelView.set(new Matrix4f(viewMatrix).setTranslation(0,0,0));
		try (RenderPass pass = encoder.createRenderPass(() -> "simpleclouds.vanillaWeather",
				main.getColorTextureView(), Optional.empty(),
				main.getDepthTextureView(), OptionalDouble.empty()))
		{
			RenderSystem.bindDefaultUniforms(pass);
			weather.render(state, pass);
			this.vanillaWeatherReplays++;
		} finally {
			modelView.popMatrix();
		}
		if ((!this.vanillaWeatherLogged || (!this.vanillaSnowLogged && !state.snowColumns.isEmpty())) && "1".equals(System.getenv("SIMPLECLOUDS_DEV"))) {
			this.vanillaWeatherLogged=true;
			if (!state.snowColumns.isEmpty()) this.vanillaSnowLogged=true;
			LOGGER.info("[DEFERRED-WEATHER] rendered rain={} snow={} intensity={} afterWorldFog=true",state.rainColumns.size(),state.snowColumns.size(),state.intensity);
		}
	}
	private boolean vanillaWeatherLogged;
	private boolean vanillaSnowLogged;

	/** Clear both original OIT attachments once, before any transparent chunk. */
	public void beginTransparency()
	{
		if (this.transparencyActive) throw new IllegalStateException("Nested cloud transparency pass");
		RenderTarget main = this.cloudDestination != null ? this.cloudDestination : Minecraft.getInstance().gameRenderer.mainRenderTarget();
		if (this.transparencyAccum == null || this.transparencyAccum.width != main.width || this.transparencyAccum.height != main.height)
		{
			if (this.transparencyAccum != null) this.transparencyAccum.destroyBuffers();
			if (this.transparencyRevealage != null) this.transparencyRevealage.destroyBuffers();
			this.transparencyAccum = new com.mojang.blaze3d.pipeline.TextureTarget("simpleclouds.originalAccum", main.width, main.height, GpuFormat.RGBA16_FLOAT, null);
			this.transparencyRevealage = new com.mojang.blaze3d.pipeline.TextureTarget("simpleclouds.originalRevealage", main.width, main.height, GpuFormat.R8_UNORM, null);
		}
		// 26.3 GlCommandEncoder configures MRT draw buffers after attachment
		// clears. On a new FBO, attachment 1 is not yet a draw buffer, so its
		// first clear is ignored (revealage remains zero -> black background).
		// Clear each texture explicitly; correct on the first offscreen frame too.
		var clearEncoder=RenderSystem.getDevice().createCommandEncoder();
		clearEncoder.clearColorTexture(this.transparencyAccum.getColorTexture(),new org.joml.Vector4f(0));
		clearEncoder.clearColorTexture(this.transparencyRevealage.getColorTexture(),new org.joml.Vector4f(1,0,0,0));
		this.transparentDraws.clear();
		this.transparencyActive = true;
	}

	/** Composite once after all chunks, never sample from the destination attachment. */
	public void endTransparency()
	{
		if (!this.transparencyActive) return;
		this.transparencyActive = false;
		if (this.transparentDraws.isEmpty()) return;
		RenderTarget main = this.cloudDestination != null ? this.cloudDestination : Minecraft.getInstance().gameRenderer.mainRenderTarget();
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		OitMrtParityProbe.Sample parity = this.transparencyMrtPipeline == null ? null : OitMrtParityProbe.begin();
		if (this.transparencyMrtPipeline == null || parity != null)
			this.drawTransparencyAttachments(encoder, main, false);
		if (parity != null)
		{
			parity.capture(this.transparencyAccum, this.transparencyRevealage, false);
			encoder.clearColorTexture(this.transparencyAccum.getColorTexture(),new org.joml.Vector4f(0));
			encoder.clearColorTexture(this.transparencyRevealage.getColorTexture(),new org.joml.Vector4f(1,0,0,0));
		}
		if (this.transparencyMrtPipeline != null)
			this.drawTransparencyAttachments(encoder, main, true);
		if (parity != null)
		{
			IndexedCloudBlend.verifyRestored();
			parity.capture(this.transparencyAccum, this.transparencyRevealage, true);
		}
		// Preview resolves opaque color and OIT together over the GUI background.
		if (this.previewActive) return;
		if ("1".equals(System.getenv("SIMPLECLOUDS_DEV"))
				&& "1".equals(System.getenv("SIMPLECLOUDS_DIAGNOSTIC_SKIP_OIT_RESOLVE"))) return;
		var compiled = RenderSystem.getCompiledPipeline(this.transparencyCompositePipeline);
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
				() -> "simpleclouds.originalTransparencyComposite", main.getColorTextureView(), Optional.empty()))
		{
			pass.setPipeline(compiled);
			pass.setUniform("AccumTexture", this.transparencyAccum.getColorTextureView(), this.stormLinearSampler);
			pass.setUniform("RevealageTexture", this.transparencyRevealage.getColorTextureView(), this.stormLinearSampler);
			pass.setVertexBuffer(0, this.triangleBuffer.slice()); pass.draw(3,1,0,0);
		}
	}

	private void drawTransparencyAttachments(CommandEncoder encoder, RenderTarget main, boolean mrt)
	{
		var accumPipeline = RenderSystem.getCompiledPipeline(this.transparencyPipeline);
		var revealPipeline = RenderSystem.getCompiledPipeline(this.transparencyRevealagePipeline);
		AbstractTexture bayer = Minecraft.getInstance().getTextureManager().getTexture(BAYER_TEXTURE);
		// Like the original whole-field pass, not two framebuffer changes per chunk.
		for (int attachment=0;attachment<(mrt?1:2);attachment++)
		{
			GpuTextureView target = attachment==0 ? this.transparencyAccum.getColorTextureView() : this.transparencyRevealage.getColorTextureView();
			try (IndexedCloudBlend scope=mrt?IndexedCloudBlend.open():null;
				RenderPass pass = mrt ? encoder.createRenderPass(RenderPassDescriptor.builder(() -> "simpleclouds.originalTransparencyMrt")
					.withColorAttachment(this.transparencyAccum.getColorTextureView())
					.withColorAttachment(this.transparencyRevealage.getColorTextureView())
					.withDepthAttachment(main.getDepthTextureView()).build())
				: encoder.createRenderPass(() -> "simpleclouds.originalTransparency", target,
					Optional.empty(), main.getDepthTextureView(), OptionalDouble.empty()))
			{
				pass.setPipeline(mrt?RenderSystem.getCompiledPipeline(this.transparencyMrtPipeline):(attachment==0 ? accumPipeline : revealPipeline));
				RenderSystem.bindDefaultUniforms(pass);
				if (this.previewActive) pass.setUniform("Projection", this.previewProjection);
				pass.setUniform("BayerMatrixSampler", bayer.getTextureView(), bayer.getSampler());
				pass.setUniform("CloudShading", this.shadingUbo);
				pass.setUniform("CloudFog", this.previewActive ? this.previewFog : this.fogUbo);
				pass.setUniform("CloudOffset", this.offsetZero);
				pass.setVertexBuffer(0, this.cubeVertexBuffer.slice());
				pass.setIndexBuffer(this.cubeIndexBuffer, IndexType.SHORT);
				for (TransparentDraw draw : this.transparentDraws)
				{
					pass.setUniform("DynamicTransforms", draw.transforms());
					pass.setUniform("CloudClip", draw.clip());
					pass.setVertexBuffer(1, draw.instances().slice());
					pass.drawIndexed(CUBE_INDICES.length, draw.count(), 0, 0, 0);
				}
			}
		}
	}

	/** Draws transparent edges into the original two attachments (no depth write). */
	private boolean loggedFirstTransparencyDraw = false;

	/** Transparency variant of {@link #drawClouds} for one chunk (A1: persistent
	 *  per-chunk GPU buffer; the fade-in alpha rides on ColorModulator.a). */
	public void drawTransparencyClouds(Matrix4f viewMatrix, GpuBuffer instances, int count, float alpha,
			float offX, float offY, float offZ)
	{
		this.drawTransparencyClouds(viewMatrix, instances, count, alpha, offX, offY, offZ, null);
	}

	public void drawTransparencyClouds(Matrix4f viewMatrix, GpuBuffer instances, int count, float alpha,
			float offX, float offY, float offZ, CloudWorldCoverage.Rect clip)
	{
		if (instances == null || count == 0)
			return;
		if (!this.transparencyActive) throw new IllegalStateException("Transparency draw outside frame pass");
		if (!this.loggedFirstTransparencyDraw)
		{
			this.loggedFirstTransparencyDraw = true;
			LOGGER.info("Simple Clouds clouds: first transparent draw, {} instances", count);
		}

// ColorModulator.a carries the fade alpha (the 1.20.1 chunk fade-in, step 3):
		Matrix4f shiftedView = new Matrix4f(viewMatrix).translate(offX, offY, offZ);
		GpuBufferSlice transforms = this.frameTransform(shiftedView, this.cloudModulator(alpha));
		GpuBufferSlice cellClip = this.cellClip(clip);
		if (transforms == null || cellClip == null)
			return;

		this.transparentDraws.add(new TransparentDraw(instances,count,transforms,cellClip));
	}

	/** Original editor target: independent depth, orthographic camera and white tint. */
	public void beginPreview(Matrix4f projection)
	{
		var main=Minecraft.getInstance().gameRenderer.mainRenderTarget();
		this.beginPreview(projection,main.width,main.height,new org.joml.Vector4f(0));
	}

	/** Independent export dimensions; never resize the user's window or main target. */
	public void beginPreview(Matrix4f projection,int width,int height,org.joml.Vector4f background)
	{
		if(width<1 || height<1 || width>8192 || height>8192 || !background.isFinite())
			throw new IllegalArgumentException("Invalid cloud image target");
		if (this.previewActive || this.cloudDestination != null || this.transparencyActive)
			throw new IllegalStateException("Nested preview render");
		if (this.previewTarget == null || this.previewTarget.width != width || this.previewTarget.height != height)
		{
			if (this.previewTarget != null) this.previewTarget.destroyBuffers();
			this.previewTarget = new com.mojang.blaze3d.pipeline.TextureTarget("simpleclouds.originalPreview",
					width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
		}
		if (this.previewProjection == null)
		{
			this.previewProjection = this.device.createBuffer(() -> "simpleclouds.previewProjection",
					GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_READ, zeros(64));
			this.previewFog = this.device.createBuffer(() -> "simpleclouds.previewFog",
					GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_READ, zeros(28));
			try (var mapping = this.previewFog.slice().map(true, false))
			{
				// Disable fog for finite preview distances without equal-edge smoothstep.
				mapping.data().putFloat(16, Float.MAX_VALUE / 2).putFloat(20, Float.MAX_VALUE).putFloat(24, 0.05F);
			}
		}
		try (var mapping = this.previewProjection.slice().map(true, false)) { writeMatrix(mapping.data(), 0, projection); }
		var clear = RenderPassDescriptor.builder(() -> "simpleclouds.originalPreviewClear")
				.withColorAttachment(this.previewTarget.getColorTextureView(), Optional.of(new org.joml.Vector4f(background)))
				.withDepthAttachment(this.previewTarget.getDepthTextureView(), OptionalDouble.of(0)).build();
		try (RenderPass ignored = this.device.createCommandEncoder().createRenderPass(clear)) { }
		this.cloudDestination = this.previewTarget;
		this.previewActive = true;
	}

	public void drawPreview(Matrix4f view, GpuBuffer instances, int count)
	{
		if (!this.previewActive) throw new IllegalStateException("Preview draw outside target");
		this.drawClouds(view, instances, count, 1, 0, 0, 0);
	}

	public void finishPreview()
	{
		if (!this.previewActive || this.transparencyActive) throw new IllegalStateException("Incomplete preview pass");
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		var compiled = RenderSystem.getCompiledPipeline(this.previewCompositePipeline);
		try (RenderPass pass = this.device.createCommandEncoder().createRenderPass(
				() -> "simpleclouds.originalPreviewComposite", main.getColorTextureView(), Optional.empty()))
		{
			pass.setPipeline(compiled);
			pass.setUniform("CloudsTexture", this.previewTarget.getColorTextureView(), this.stormLinearSampler);
			pass.setUniform("AccumTexture", this.transparencyAccum.getColorTextureView(), this.stormLinearSampler);
			pass.setUniform("RevealageTexture", this.transparencyRevealage.getColorTextureView(), this.stormLinearSampler);
			pass.setVertexBuffer(0, this.triangleBuffer.slice()); pass.draw(3, 1, 0, 0);
		}
	}

	/** Restore world state even if generation, a draw or shader compilation fails. */
	public RenderTarget resolvePreviewForExport()
	{
		RenderSystem.assertOnRenderThread();
		if(this.previewActive || this.previewTarget==null || this.transparencyAccum==null)
			throw new IllegalStateException("No completed cloud preview to export");
		if(this.previewExportTarget==null || this.previewExportTarget.width!=this.previewTarget.width
				|| this.previewExportTarget.height!=this.previewTarget.height) {
			if(this.previewExportTarget!=null) this.previewExportTarget.destroyBuffers();
			this.previewExportTarget=new com.mojang.blaze3d.pipeline.TextureTarget("simpleclouds.previewExport",
					this.previewTarget.width,this.previewTarget.height,GpuFormat.RGBA8_UNORM,null);
		}
		try(RenderPass pass=this.device.createCommandEncoder().createRenderPass(
				()->"simpleclouds.previewExportResolve",this.previewExportTarget.getColorTextureView(),Optional.empty())) {
			// Write straight RGBA with no scene blend: includes both opaque and OIT
			// clouds, preserves coverage alpha, excludes world terrain and GUI.
			pass.setPipeline(RenderSystem.getCompiledPipeline(this.previewExportPipeline));
			pass.setUniform("CloudsTexture",this.previewTarget.getColorTextureView(),this.stormLinearSampler);
			pass.setUniform("AccumTexture",this.transparencyAccum.getColorTextureView(),this.stormLinearSampler);
			pass.setUniform("RevealageTexture",this.transparencyRevealage.getColorTextureView(),this.stormLinearSampler);
			pass.setVertexBuffer(0,this.triangleBuffer.slice()); pass.draw(3,1,0,0);
		}
		return this.previewExportTarget;
	}
	public com.mojang.renderpearl.api.textures.GpuSampler previewSampler() {return this.stormLinearSampler;}

	public void abortPreview()
	{
		this.previewActive = false;
		this.cloudDestination = null;
		this.transparencyActive = false;
		this.transparentDraws.clear();
	}

	/**
	 * Renders the cloud instances into the top-down ortho shadow depth target
	 * (cleared to 1.0; a cloud writes its window-space depth per XZ texel).
	 */
	/** One per-chunk instance source (A1: the shadow pass draws the persistent
	 *  per-chunk GPU buffers, one drawIndexed per chunk inside a single pass). */
	public record InstanceSource(GpuBuffer buffer, int count, CloudWorldCoverage.Rect clip)
	{
		public InstanceSource(GpuBuffer buffer, int count) { this(buffer, count, null); }
	}

	/** Original storm geometry volume; no CPU coverage map or geometry readback. */
	public void renderOriginalStormShadow(double camX, double camZ, float cloudHeight,
			float span, float angle, float windX, float windZ, List<InstanceSource> sources)
	{
		this.stormShadowRendered = false;
		if (sources == null || sources.isEmpty()) return;
		this.stormShadowView.set(OriginalStormShadowTransform.view(camX, camZ, cloudHeight, span, angle, windX, windZ));
		this.stormShadowProjection.set(OriginalStormShadowTransform.projection(span));
		this.stormShadowSlot = (this.stormShadowSlot + 1) % 3;
		GpuBuffer matrices = this.stormShadowMatricesRing[this.stormShadowSlot];
		GpuBuffer params = this.stormShadowParamsRing[this.stormShadowSlot];
		try (var mapping = matrices.slice().map(true, false))
		{
			writeMatrix(mapping.data(), 0, this.stormShadowView);
			writeMatrix(mapping.data(), 64, this.stormShadowProjection);
		}
		try (var mapping = params.slice().map(true, false))
		{
			mapping.data().putFloat(0, cloudHeight).putFloat(4, 1.0F / OriginalStormShadowTransform.CLOUD_SCALE);
		}
		var compiled = RenderSystem.getCompiledPipeline(this.stormShadowPipeline);
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		try (RenderPass pass = encoder.createRenderPass(() -> "simpleclouds.originalStormShadow",
				this.stormShadowTarget.getColorTextureView(), Optional.of(new org.joml.Vector4f()),
				this.stormShadowTarget.getDepthTextureView(), OptionalDouble.of(1.0)))
		{
			pass.setPipeline(compiled);
			pass.setUniform("ShadowMatrices", matrices);
			pass.setUniform("StormShadow", params);
			pass.setVertexBuffer(0, this.quadVertexBuffer.slice());
			pass.setIndexBuffer(this.quadIndexBuffer, IndexType.SHORT);
			for (InstanceSource source : sources)
			{
				if (source.count() <= 0) continue;
				pass.setVertexBuffer(1, source.buffer().slice());
				pass.drawIndexed(QUAD_INDICES.length, source.count(), 0, 0, 0);
			}
		}
		this.stormShadowRendered = true;
	}

	public void renderCloudShadowMap(double camX, double camY, double camZ, float cloudHeight, List<InstanceSource> sources)
	{
		if (sources == null || sources.isEmpty())
		{
			this.shadowRenderedThisFrame = false;
			return;
		}

		// Step 1: world-anchored light volume (never camera-relative). Top-down ortho
		// centered on the camera in XZ; joml's setOrtho looks down -Z, so
		// viewZ = worldY - lightPlane in [-depthFar, 0]: light plane (top of the
		// cloud volume) at viewZ=0 (window depth ~0.0), deepest point at -depthFar
		// (~1.0). Map clears to 1.0; LESS_THAN keeps the cloud CLOSEST to the light.
		// Step 6: joml 1.10's 16-float set() takes COLUMN-major arguments (verified
		// by point transform): each 4-float group is one COLUMN, its 4th value the
		// w-row element. The original code passed ROW-major groups, silently
		// transposing the view (non-affine garbage w-row) and clipping every
		// fragment — the shadow map was 100% empty, i.e. no terrain shadow at all.
		// Columns: col0=(1,0,0,0); col1=(0,0,1,0) [viewY=worldZ]; col2=(0,1,0,0) [viewZ=worldY];
		// col3=translation (-camX, -camZ, -lightPlane) — the translation is the FOURTH
		// group in column-major order, not the 4th element of each group.
		float lightPlane = cloudHeight + SHADOW_VOLUME_TOP;
		float depthFar = SHADOW_VOLUME_TOP + SHADOW_VOLUME_BELOW;
		org.joml.Matrix4f shadowView = new org.joml.Matrix4f().set(
				1.0F, 0.0F, 0.0F, 0.0F,
				0.0F, 0.0F, 1.0F, 0.0F,
				0.0F, 1.0F, 0.0F, 0.0F,
				(float) -camX, (float) -camZ, -lightPlane, 1.0F);
		org.joml.Matrix4f shadowProj = new org.joml.Matrix4f().setOrtho(
				-SHADOW_RADIUS, SHADOW_RADIUS, -SHADOW_RADIUS, SHADOW_RADIUS, 0.0F, depthFar, true); // 26.3 ZERO_TO_ONE clip control

		this.shadowMatricesIdx = (this.shadowMatricesIdx + 1) % 3;
		GpuBuffer matricesBuf = this.shadowMatricesRing[this.shadowMatricesIdx];
		try (var view = matricesBuf.slice().map(true, false))
		{
			ByteBuffer data = view.data();
			writeMatrix(data, 0, shadowView);
			writeMatrix(data, 64, shadowProj);
		}

		GpuTextureView colorView = this.shadowTarget.getColorTextureView();
		GpuTextureView depthView = this.shadowTarget.getDepthTextureView();
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		var compiledShadowPipeline = RenderSystem.getCompiledPipeline(this.shadowPipeline);
		// Mapping/uploading inside an open 26.3 pass is forbidden. Prepare all
		// distinct slices before encoding that pass, not in its draw loop.
		java.util.List<GpuBufferSlice> clips = new java.util.ArrayList<>(sources.size());
		for (InstanceSource source : sources) clips.add(this.cellClip(source.clip()));
		RenderPass pass = encoder.createRenderPass(() -> "simpleclouds.shadowMap", colorView, Optional.empty(), depthView, OptionalDouble.of(1.0));
		pass.setPipeline(compiledShadowPipeline);
		pass.setUniform("ShadowMatrices", matricesBuf);
		pass.setVertexBuffer(0, this.quadVertexBuffer.slice());
		for (int i = 0; i < sources.size(); i++)
		{
			InstanceSource source = sources.get(i);
			GpuBufferSlice clip = clips.get(i);
			if (clip == null) continue;
			pass.setUniform("CloudClip", clip);
			pass.setVertexBuffer(1, source.buffer().slice());
			pass.setIndexBuffer(this.quadIndexBuffer, IndexType.SHORT);
			pass.drawIndexed(QUAD_INDICES.length, source.count(), 0, 0, 0);
		}
		pass.close();
		this.shadowRenderedThisFrame = true;
	}

	/**
	 * Fullscreen terrain cloud-shadow pass: reconstructs world positions from the
	 * scene depth and darkens fragments under the cloud shadow map.
	 */
	public void drawTerrainShadows(Matrix4f viewMatrix, double camX, double camY, double camZ, float cloudHeight, float minimumRadius)
	{
		if (!TERRAIN_SHADOWS_ENABLED)
			return;
		if (!this.shadowRenderedThisFrame)
			return;

		// Same world-anchored light volume as renderCloudShadowMap (step 1); same
		// column-major set() construction (see the Step 6 note there).
		float lightPlane = cloudHeight + SHADOW_VOLUME_TOP;
		float depthFar = SHADOW_VOLUME_TOP + SHADOW_VOLUME_BELOW;
		org.joml.Matrix4f shadowView = new org.joml.Matrix4f().set(
				1.0F, 0.0F, 0.0F, 0.0F,
				0.0F, 0.0F, 1.0F, 0.0F,
				0.0F, 1.0F, 0.0F, 0.0F,
				(float) -camX, (float) -camZ, -lightPlane, 1.0F);
		org.joml.Matrix4f shadowProj = new org.joml.Matrix4f().setOrtho(
				-SHADOW_RADIUS, SHADOW_RADIUS, -SHADOW_RADIUS, SHADOW_RADIUS, 0.0F, depthFar, true); // 26.3 ZERO_TO_ONE clip control
		org.joml.Matrix4f shadowViewProj = new org.joml.Matrix4f(shadowProj).mul(shadowView); // mul() mutates

		this.terrainPassIdx = (this.terrainPassIdx + 1) % 3;
		GpuBuffer terrainBuf = this.terrainPassRing[this.terrainPassIdx];
		try (var view = terrainBuf.slice().map(true, false))
		{
			ByteBuffer data = view.data();
			writeMatrix(data, 0, shadowViewProj);
			data.putFloat(64, SHADOW_BIAS);
			// Step 6: the original cloud_shadows distance-model uniforms.
			data.putFloat(68, minimumRadius);
			data.putFloat(72, SHADOW_RADIUS * 2.0F);
			data.putFloat(76, SHADOW_FADE_DISTANCE);
			data.putFloat(80, DEBUG_SHOW_DEPTH);
			data.putFloat(84, (float) camX);
			data.putFloat(88, (float) camY);
			data.putFloat(92, (float) camZ);
		}

		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		GpuTextureView colorView = main.getColorTextureView();
		GpuTextureView depthView = main.getDepthTextureView();

		GpuBufferSlice terrainTransform = this.frameTransform(viewMatrix, null);
		if (terrainTransform == null)
			return;
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		// COLOR-ONLY pass: attaching the main depth view as the pass's depth-stencil
		// attachment AND sampling it as a texture in the same pass is undefined
		// (reads back garbage) — the atmospheric pass (line below) uses this 2-arg
		// overload for exactly this reason.
		var compiledTerrainPipeline = RenderSystem.getCompiledPipeline(this.terrainPipeline);
		RenderPass pass = encoder.createRenderPass(() -> "simpleclouds.terrainShadows", colorView, Optional.empty());
		pass.setPipeline(compiledTerrainPipeline);
		RenderSystem.bindDefaultUniforms(pass);
		pass.setUniform("DynamicTransforms", terrainTransform);
		pass.setUniform("ShadowPass", terrainBuf);
		pass.setUniform("DepthSampler", depthView, this.nearestSampler);
		// Step 6: the original multiplies the shadowed scene color by
		// ShadowColorMultiplier, so the pass samples the scene color itself.
		pass.setUniform("DiffuseSampler", colorView, this.nearestSampler);
		pass.setUniform("ShadowMap", this.shadowTarget.getDepthTextureView(), this.nearestSampler);
		pass.setVertexBuffer(0, this.triangleBuffer.slice());
		pass.draw(3, 1, 0, 0);
		pass.close();
	}

	// ------------------------------------------------------------------
	// Atmospheric clouds (26.2 port of the 1.20.1 post-chain atmospheric layer)
	// ------------------------------------------------------------------

	/**
	 * 1.20.1 lightning bolt pass: world-space quad sections (7 floats/vertex:
	 * position + rgba), additive blend, depth-tested against the scene (inverted-Z
	 * GREATER_THAN_OR_EQUAL), no depth write, no fog.
	 */
	public void drawLightning(Matrix4f viewMatrix, java.nio.ByteBuffer vertexData, int vertexCount)
	{
		this.drawLightning(viewMatrix, vertexData, vertexCount, null, null);
	}

	/**
	 * @param dhDepth Distant Horizons LOD depth (reversed-Z), or null without DH
	 * @param dhInverseProjection inverse of DH's projection for that depth, or null
	 */
	public void drawLightning(Matrix4f viewMatrix, java.nio.ByteBuffer vertexData, int vertexCount,
			@Nullable GpuTextureView dhDepth, @Nullable Matrix4f dhInverseProjection)
	{
		if (vertexCount <= 0)
			return;
		if (vertexCount % 24 != 0)
			throw new IllegalArgumentException("Lightning requires complete 24-vertex sections");
		int sections = vertexCount / 24;
		// Recreation (not resize — the 26.2 GlBuffer has no resize): bolts live at
		// most a couple of seconds, so the churn is bounded and infrequent.
		if (this.lightningVertexBuffer != null)
			this.lightningVertexBuffer.close();
		this.lightningVertexBuffer = this.device.createBuffer(() -> "simpleclouds.lightning", GpuBuffer.USAGE_VERTEX, vertexData);
		var transforms = this.frameTransform(viewMatrix, null);
		if (transforms == null)
			return;
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		GpuTextureView colorView = main.getColorTextureView();
		GpuTextureView depthView = main.getDepthTextureView();
		boolean occlude = dhDepth != null && dhInverseProjection != null && dhInverseProjection.isFinite();
		this.lightningOcclusionSlot = (this.lightningOcclusionSlot + 1) % 3;
		GpuBuffer occlusion = this.lightningOcclusionRing[this.lightningOcclusionSlot];
		try (var mapping = occlusion.slice().map(true, false))
		{
			(occlude ? dhInverseProjection : new Matrix4f()).get(0, mapping.data());
			mapping.data().putFloat(64, occlude ? 1.0F : 0.0F).putFloat(68, 1.0F / main.width)
					.putFloat(72, 1.0F / main.height).putFloat(76, 0.0F);
		}
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		var compiledLightningPipeline = RenderSystem.getCompiledPipeline(this.lightningPipeline);
		try (RenderPass pass = encoder.createRenderPass(() -> "simpleclouds.lightning", colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
		pass.setPipeline(compiledLightningPipeline);
		RenderSystem.bindDefaultUniforms(pass);
		pass.setUniform("DynamicTransforms", transforms);
		pass.setUniform("LightningOcclusion", occlusion);
		// Never sampled while disabled; any depth view not attached to this pass will do.
		pass.setUniform("DhDepthSampler", occlude ? dhDepth : this.stormShadowTarget.getDepthTextureView(), this.nearestSampler);
		pass.setIndexBuffer(this.lightningIndexBuffer, IndexType.SHORT);
		// Reuse the fixed index window for every section; never truncate a large bolt.
		for (int first = 0; first < sections; first += 4096 / 24)
		{
			int count = Math.min(sections - first, 4096 / 24);
			pass.setVertexBuffer(0, this.lightningVertexBuffer.slice((long)first * 24 * 7 * Float.BYTES,
				(long)count * 24 * 7 * Float.BYTES));
			pass.drawIndexed(count * 36, 1, 0, 0, 0);
		}
		}
	}

/**
	 * Draws one atmospheric-cloud formation pass over the whole view. The shader
	 * samples a reusable copy of the scene, avoiding texture feedback while
	 * writing the composited result. The renderer calls it after the cloud passes.
	 *
	 * @param viewMatrix world -&gt; camera
	 * @param transform the formation's scale+wind-yaw matrix (shader's Transform)
	 * @param shift the formation's time offset (shader's ShiftMovement)
	 * @param densityMult 0..1 cross-fade multiplier (transition blending)
	 * @param density the formation's base density
	 * @param projection the active world projection, including camera effects
	 */
	public void drawAtmosphericClouds(org.joml.Matrix4f viewMatrix, org.joml.Matrix4f projection, org.joml.Matrix2f transform,
			float shift, float densityMult, float density, float r, float g, float b, float a)
	{
		float cloudDensity = density * densityMult;
		if (cloudDensity <= 0.01F)
			return;
		// During the first loading frame the extracted camera can still have a
		// zero/uninitialised projection. Defer this optional layer until ready;
		// do not poison the entire cloud render pass with an inverse of zero.
		if (projection == null || !projection.isFinite() || !viewMatrix.isFinite()
				|| projection.determinant() == 0.0F || viewMatrix.determinant() == 0.0F)
			return;

		this.atmosphericIdx = (this.atmosphericIdx + 1) % 3;
		GpuBuffer buf = this.atmosphericRing[this.atmosphericIdx];
		try (var view = buf.slice().map(true, false))
		{
			OriginalAtmosphericUniforms.write(view.data(), projection, viewMatrix, transform,
					shift, cloudDensity, r, g, b, a);
		}

		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		GpuTextureView colorView = main.getColorTextureView();

		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		if (this.atmosphericSource == null || this.atmosphericSource.width != main.width || this.atmosphericSource.height != main.height)
		{
			var replacement = new com.mojang.blaze3d.pipeline.TextureTarget("simpleclouds.atmosphericSource", main.width, main.height, main.getColorTexture().getFormat(), null);
			if (this.atmosphericSource != null) this.atmosphericSource.destroyBuffers();
			this.atmosphericSource = replacement;
		}
		encoder.copyTextureToTexture(main.getColorTexture(), this.atmosphericSource.getColorTexture(), 0,0,0,0,0, main.width,main.height);
		// Runs immediately after the sky. Subsequent terrain/cloud/weather draws
		// occlude this layer naturally, matching original ordering without a mask.
		var compiledAtmosphericPipeline = RenderSystem.getCompiledPipeline(this.atmosphericPipeline);
		try (RenderPass pass = encoder.createRenderPass(() -> "simpleclouds.atmosphericClouds", colorView, Optional.empty()))
		{
			pass.setPipeline(compiledAtmosphericPipeline);
			pass.setUniform("AtmosphericPass", buf);
			pass.setUniform("DiffuseSampler", this.atmosphericSource.getColorTextureView(), this.nearestSampler);
			pass.setVertexBuffer(0, this.triangleBuffer.slice());
			pass.draw(3, 1, 0, 0);
		}
	}

	/** Two reusable full-scene targets avoid screen-door holes while retaining
	 * independent terrain/cloud depth tests for each generation. */
	public void beginCloudTransition()
	{
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		if (this.transitionOld == null || this.transitionOld.width != main.width || this.transitionOld.height != main.height)
		{
			if (this.transitionOld != null) this.transitionOld.destroyBuffers();
			if (this.transitionNew != null) this.transitionNew.destroyBuffers();
			this.transitionOld = new com.mojang.blaze3d.pipeline.TextureTarget("simpleclouds.oldScene", main.width, main.height, main.getColorTexture().getFormat(), GpuFormat.D32_FLOAT);
			this.transitionNew = new com.mojang.blaze3d.pipeline.TextureTarget("simpleclouds.newScene", main.width, main.height, main.getColorTexture().getFormat(), GpuFormat.D32_FLOAT);
		}
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		encoder.copyTextureToTexture(main.getColorTexture(), this.transitionOld.getColorTexture(), 0,0,0,0,0, main.width,main.height);
		encoder.copyTextureToTexture(main.getColorTexture(), this.transitionNew.getColorTexture(), 0,0,0,0,0, main.width,main.height);
		this.transitionOld.copyDepthFrom(main);
		this.transitionNew.copyDepthFrom(main);
	}

	public void selectCloudTransitionScene(boolean old)
	{
		this.cloudDestination = old ? this.transitionOld : this.transitionNew;
	}

	public void finishCloudTransition(float progress)
	{
		this.cloudDestination = null;
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		var transform = this.frameTransform(new Matrix4f(), new org.joml.Vector4f(1,1,1,progress));
		if (transform == null)
			return;
		var compiledTransitionPipeline = RenderSystem.getCompiledPipeline(this.transitionPipeline);
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
				() -> "simpleclouds.transition", main.getColorTextureView(), Optional.empty()))
		{
			pass.setPipeline(compiledTransitionPipeline);
			pass.setUniform("DynamicTransforms", transform);
			pass.setUniform("OldScene", this.transitionOld.getColorTextureView(), this.nearestSampler);
			pass.setUniform("NewScene", this.transitionNew.getColorTextureView(), this.nearestSampler);
			pass.setVertexBuffer(0, this.triangleBuffer.slice());
			pass.draw(3,1,0,0);
		}
		// Later rain/bolts use the incoming geometry's occlusion.
		main.copyDepthFrom(this.transitionNew);
	}

	public void resetCloudDestination() { this.cloudDestination = null; }

	private static ByteBuffer zeros(int size)
	{
		// position 0 / limit size: GpuDevice rejects empty sources (position == limit).
		return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
	}

	/** Writes a column-major joml matrix into a std140 slot at the given byte offset. */
	private static void writeMatrix(ByteBuffer data, int offset, org.joml.Matrix4f matrix)
	{
		float[] m = new float[16];
		matrix.get(m);
		for (int i = 0; i < 16; i++)
			data.putFloat(offset + i * 4, m[i]);
	}

	// Own DynamicUniforms (not the shared per-frame one): our passes are encoded at the
	// tail of the level render but execute later in the frame, and the shared ring can be
	// re-written by vanilla passes in between (zeroing ModelViewMat / ColorModulator, which
	// made every cloud fragment vanish). A private ring with fences is safe across the gap.
	// 26.3 removed net.minecraft.client.renderer.DynamicUniforms; CloudTransformRing is the
	// part of it we used (same DynamicTransforms block, own fenced ring).
	private final CloudTransformRing ownTransforms = new CloudTransformRing("simpleclouds.cloudTransforms", TRANSFORM_SLOT_CAP);

	// Vanilla resets its shared DynamicUniforms once per frame; this private one was never
	// reset, so every frame appended to the same storage and it doubled without end (65536
	// slots after the 64-view STORM run, each replaced buffer kept until shutdown).
	// reset() -> DynamicUniformStorage.endFrame(): write position back to 0, rotate to the
	// next of the three fenced ring buffers (slices of earlier frames stay intact until the
	// GPU is done with them) and free the buffers that growth replaced.
	public static final int TRANSFORM_SLOT_CAP = 16384;
	private int frameTransforms;
	private int peakFrameTransforms;
	private boolean transformCapReported;

	/** The original multiplies each cloud pass by the vanilla cloud colour,
	 * locally darkened by storms and briefly brightened by nearby lightning. */
	private float cloudR = 1.0F, cloudG = 1.0F, cloudB = 1.0F;

	public void setCloudColor(float r, float g, float b)
	{
		this.cloudR = r;
		this.cloudG = g;
		this.cloudB = b;
	}

	private org.joml.Vector4f cloudModulator(float alpha)
	{
		if (this.previewActive) return null;
		if (this.cloudR == 1.0F && this.cloudG == 1.0F && this.cloudB == 1.0F && alpha >= 1.0F)
			return null;
		return new org.joml.Vector4f(this.cloudR, this.cloudG, this.cloudB, alpha);
	}

	/** Once per rendered frame, before the first transform of that frame. */
	public void beginFrame()
	{
		this.cloudDepthReady=false;
		this.worldFogDraws=0;
		this.vanillaWeatherReplays=0;
		this.stormFogReady=false;
		this.ownTransforms.reset();
		this.cellClips.reset();
		this.peakFrameTransforms = Math.max(this.peakFrameTransforms, this.frameTransforms);
		this.frameTransforms = 0;
	}

	/** Peak transform slots used by one frame since the last call (30 s summary). */
	public int takePeakFrameTransforms()
	{
		int peak = Math.max(this.peakFrameTransforms, this.frameTransforms);
		this.peakFrameTransforms = 0;
		return peak;
	}

	/**
	 * Every per-draw transform slice (distinct model-view, offset and alpha per draw) comes from
	 * here. Above TRANSFORM_SLOT_CAP in one frame the draw is skipped and the overflow is logged
	 * as an error once, instead of growing the storage without bound or passing silently.
	 * Returns null over the cap; color may be null (plain white modulator).
	 */
	private GpuBufferSlice frameTransform(Matrix4f modelView, org.joml.Vector4f color)
	{
		if (++this.frameTransforms > TRANSFORM_SLOT_CAP)
		{
			if (!this.transformCapReported)
			{
				this.transformCapReported = true;
				LOGGER.error("Simple Clouds ERROR: dynamic transform cap exceeded ({} slots in one frame, cap {}); further draws in such frames are skipped",
						this.frameTransforms, TRANSFORM_SLOT_CAP);
			}
			return null;
		}
		return color == null ? this.ownTransforms.writeTransform(modelView) : this.ownTransforms.writeTransform(modelView, color);
	}

	private boolean clipCapReported;
	private GpuBufferSlice cellClip(CloudWorldCoverage.Rect bounds)
	{
		if (bounds == null) return this.clipDisabled.slice();
		GpuBufferSlice slice = this.cellClips.write(bounds);
		if (slice == null && !this.clipCapReported)
		{
			this.clipCapReported = true;
			LOGGER.error("Simple Clouds ERROR: world-cell clip cap exceeded ({} per frame); further clipped draws skipped", TRANSFORM_SLOT_CAP);
		}
		return slice;
	}


	@Override

	public void close()
	{
		if (this.previewTarget != null) this.previewTarget.destroyBuffers();
		if (this.previewExportTarget != null) this.previewExportTarget.destroyBuffers();
		if (this.previewProjection != null) this.previewProjection.close();
		if (this.previewFog != null) this.previewFog.close();
		this.transparentDraws.clear();
		if (this.transparencyAccum != null) this.transparencyAccum.destroyBuffers();
		if (this.transparencyRevealage != null) this.transparencyRevealage.destroyBuffers();
		if (this.atmosphericSource != null) this.atmosphericSource.destroyBuffers();
		if (this.worldFogSource != null) this.worldFogSource.destroyBuffers();
		if (this.cloudDepthSnapshot != null) this.cloudDepthSnapshot.destroyBuffers();
		if (this.preCloudDepthSnapshot != null) this.preCloudDepthSnapshot.destroyBuffers();
		if (this.dhFadeGuardSaved != null) this.dhFadeGuardSaved.destroyBuffers();
		for(GpuBuffer buffer:this.worldFogRing) buffer.close();
		if (this.transitionOld != null) this.transitionOld.destroyBuffers();
		if (this.transitionNew != null) this.transitionNew.destroyBuffers();
		// A1: the per-chunk instance GpuBuffers are owned by the renderer's chunk cache
		// (freed there on eviction / shutdown).
		this.triangleBuffer.close();
		for (GpuBuffer b : this.stormFogRing) b.close();
		for (GpuBuffer b : this.dhDepthMergeRing) b.close();
		if (this.dhDepthScratch != null) this.dhDepthScratch.destroyBuffers();
		if (this.dhDepthBackup != null) this.dhDepthBackup.destroyBuffers();
		for (GpuBuffer b : this.lightningOcclusionRing) b.close();
		if (this.originalStormFogTarget != null) this.originalStormFogTarget.destroyBuffers();
		if (this.stormBlurMain != null) this.stormBlurMain.destroyBuffers();
		if (this.stormBlurSwap != null) this.stormBlurSwap.destroyBuffers();
		for (GpuBuffer b : this.stormBlurRing) b.close();
		this.stormLinearSampler.close();
		this.shadowTarget.destroyBuffers();
		this.stormShadowTarget.destroyBuffers();
		for (GpuBuffer b : this.stormShadowMatricesRing) b.close();
		for (GpuBuffer b : this.stormShadowParamsRing) b.close();
		for (GpuBuffer b : this.shadowMatricesRing)
			b.close();
		for (GpuBuffer b : this.terrainPassRing)
			b.close();
		for (GpuBuffer b : this.atmosphericRing)
			b.close();
		for (GpuBuffer b : this.shadingModes)
			b.close();
		if (this.lightningVertexBuffer != null)
			this.lightningVertexBuffer.close();
		this.lightningIndexBuffer.close();
		this.nearestSampler.close();
		this.quadVertexBuffer.close();
		this.quadIndexBuffer.close();
		this.cubeVertexBuffer.close();
		this.cubeIndexBuffer.close();
		this.lightingUbo.close();
		this.shadingUbo.close();
		this.fogUbo.close();
		this.ownTransforms.close();
		this.cellClips.close();
		this.clipDisabled.close();
	}
}

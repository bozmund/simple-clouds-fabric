package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
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
	private static final Identifier STORM_FOG_LOCATION = SimpleCloudsMod.id("core/storm_fog");
	private static final Identifier SKY_FLASH_LOCATION = SimpleCloudsMod.id("core/sky_flash");
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

	private final RenderPipeline pipeline;
	private final RenderPipeline previewPipeline;
	private final RenderPipeline transparencyPipeline;
	private final GpuBuffer quadVertexBuffer;
	private final GpuBuffer quadIndexBuffer;
	private final GpuBuffer lightingUbo;
	private final GpuBuffer shadingUbo;

	// Lightning bolts (1.20.1 port): world-space quads, additive blend, depth
	// tested against terrain, no depth write. One dynamic vertex upload per frame.
	private static final com.mojang.blaze3d.vertex.VertexFormat LIGHTNING_FORMAT = com.mojang.blaze3d.vertex.VertexFormat.builder(0)
			.addAttribute("Position", GpuFormat.RGB32_FLOAT)
			.addAttribute("Color", GpuFormat.RGBA32_FLOAT)
			.build();
	private static final Identifier LIGHTNING_LOCATION = SimpleCloudsMod.id("core/lightning");
	private final RenderPipeline lightningPipeline;
	private GpuBuffer lightningVertexBuffer;
	private final GpuBuffer lightningIndexBuffer;
	private final GpuBuffer fogUbo;
	// Per-frame ring for the runtime-toggleable CloudShading.UseNormals (config
	// cubeNormals): the static shading UBO cannot be remapped every frame (26.2
	// per-frame-UBO rule), so the toggle rides on a ring.
	private final GpuBuffer[] useNormalsRing = new GpuBuffer[3];
	private int useNormalsRingSlot = 0;
	private static final float[] CLOUD_SHADING_BASE = { 0.0F, 0.0F, 0.15F };
	private final RenderPipeline stormFogPipeline;
	private final GpuBuffer triangleBuffer;
	private final GpuBuffer stormFogUbo;
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
			super(label, useDepth, format);
		}
	}

	private final SimpleRenderTarget shadowTarget;
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
				.withSampler("BayerMatrixSampler")
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
				.withDepthStencilState(DepthStencilState.DEFAULT)
				.build();

		// 26.2 previewer pass: the main clouds shader (snippet pipeline) into the
		// MAIN frame with a screen-controlled orbit view matrix and NO depth test
		// (the box must float over the world; offscreen color targets do not
		// receive fragments from standalone passes in 26.2 -- see PreviewDrawPipeline).
		this.previewPipeline = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
				.withLocation(CLOUDS_LOCATION)
				.withVertexShader(CLOUDS_VSH)
				.withFragmentShader(CLOUDS_FSH)
				.withBindGroupLayout(bgl)
				.withVertexBinding(0, CloudVertexFormat.QUAD_FORMAT)
				.withVertexBinding(1, CloudVertexFormat.INSTANCE_FORMAT)
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false)
				.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
				// ALWAYS_PASS + no write: the preview box draws over the world without
				// touching the depth buffer (Optional.empty() here is invalid for the
				// main target and silently discards the draw).
				.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
				.build();

		// Transparency pass: standard alpha blending into the main target, depth test on
		// but no depth write (drawn after the opaque clouds). 1.20.1's weighted-blended
		// two-attachment + composite variant is not ported to 26.2 yet (see
		// clouds_transparency.fsh header).
		BindGroupLayout transparencyBgl = BindGroupLayout.builder()
				.withUniform("CloudShading", UniformType.UNIFORM_BUFFER)
				.withUniform("CloudFog", UniformType.UNIFORM_BUFFER)
				.withSampler("BayerMatrixSampler")
				.build();
		this.transparencyPipeline = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
				.withLocation(CLOUDS_TRANSPARENCY_LOCATION)
				.withVertexShader(CLOUDS_TRANSPARENCY_LOCATION)
				.withFragmentShader(CLOUDS_TRANSPARENCY_LOCATION)
				.withBindGroupLayout(transparencyBgl)
				.withVertexBinding(0, CloudVertexFormat.QUAD_FORMAT)
				.withVertexBinding(1, CloudVertexFormat.INSTANCE_ALPHA_FORMAT)
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false)
				.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
				// GREATER_THAN_OR_EQUAL = the inverted-Z "closer or equal" test: LESS_THAN
				// (normal-Z) can never pass against a cleared (far=0.0) depth, which would
				// make every transparent face in open air invisible.
				.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
				.build();

		// Storm fog overlay: fullscreen triangle, blended, no depth test (drawn last,
		// on top of everything in the scene).
		BindGroupLayout stormFogBgl = BindGroupLayout.builder()
				.withUniform("StormFog", UniformType.UNIFORM_BUFFER)
				.build();
		VertexFormat triangleFormat = VertexFormat.builder(0)
				.addAttribute(CloudVertexFormat.POSITION, GpuFormat.RG32_FLOAT)
				.build();
		this.stormFogPipeline = RenderPipeline.builder()
				.withLocation(STORM_FOG_LOCATION)
				.withVertexShader(STORM_FOG_LOCATION)
				.withFragmentShader(STORM_FOG_LOCATION)
				.withBindGroupLayout(stormFogBgl)
				.withVertexBinding(0, triangleFormat)
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false)
				.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
				.withDepthStencilState(Optional.empty())
				.build();

		ByteBuffer triangleData = ByteBuffer.allocateDirect(FULLSCREEN_TRIANGLE.length * 4).order(ByteOrder.nativeOrder());
		for (float v : FULLSCREEN_TRIANGLE)
			triangleData.putFloat(v);
		triangleData.flip();
		this.triangleBuffer = device.createBuffer(() -> "simpleclouds.fullscreenTriangle", GpuBuffer.USAGE_VERTEX, triangleData);

		this.stormFogUbo = device.createBuffer(() -> "simpleclouds.stormFog", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_READ, 32L);

		// Sky flash (storm plan step 1): same fullscreen-triangle shape as the
		// storm fog — a short white brightening of the whole screen (see
		// core/sky_flash.fsh for why the port draws this itself).
		BindGroupLayout skyFlashBgl = BindGroupLayout.builder()
				.withUniform("SkyFlash", UniformType.UNIFORM_BUFFER)
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

		BindGroupLayout shadowBgl = BindGroupLayout.builder()
				.withUniform("ShadowMatrices", UniformType.UNIFORM_BUFFER)
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
				.withSampler("DepthSampler")
				.withSampler("DiffuseSampler")
				.withSampler("ShadowMap")
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
				.withSampler("DiffuseSampler")
				.build();
		// The shader writes the fully-composited color (it samples the main target
		// itself), so no blending and no depth read -- same shape as the storm fog
		// pass.
		this.atmosphericPipeline = RenderPipeline.builder()
				.withLocation(ATMOSPHERIC_CLOUDS_LOCATION)
				.withVertexShader(ATMOSPHERIC_CLOUDS_LOCATION)
				.withFragmentShader(ATMOSPHERIC_CLOUDS_LOCATION)
				.withBindGroupLayout(atmosphericBgl)
				.withVertexBinding(0, triangleFormat)
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false)
				.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
				.withDepthStencilState(Optional.empty())
				.build();

		this.lightningPipeline = RenderPipeline.builder()
				.withLocation(LIGHTNING_LOCATION)
				.withVertexShader(LIGHTNING_LOCATION)
				.withFragmentShader(LIGHTNING_LOCATION)
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
			this.terrainPassRing[slot] = device.createBuffer(() -> "simpleclouds.terrainShadowPass" + slot, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_READ, zeros(112));
			// std140 AtmosphericPass: mat4(64) + mat2(16) + 9 floats(36) + vec4(16,
			// padded to offset 120) = 136 bytes -> 144 to be safe.
			this.atmosphericRing[slot] = device.createBuffer(() -> "simpleclouds.atmospheric" + slot, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_READ, zeros(144));
			// CloudShading layout: vec3 darkness(12) + float useNormals(4) = 16 bytes.
			this.useNormalsRing[slot] = device.createBuffer(() -> "simpleclouds.useNormals" + slot, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_READ, zeros(16));
		}
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
	public void drawClouds(Matrix4f viewMatrix, GpuBuffer instances, int count, float alpha)
	{
		if (instances == null || count == 0)
			return;
		if (!this.loggedFirstDraw)
		{
			this.loggedFirstDraw = true;
			LOGGER.info("Simple Clouds clouds: first draw, {} instances", count);
		}

		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		GpuTextureView colorView = main.getColorTextureView();
		GpuTextureView depthView = main.getDepthTextureView();

		// DynamicTransforms is not covered by bindDefaultUniforms, so bind it explicitly as
		// vanilla CloudRenderer does. Written into vanilla's shared per-frame ring buffer (reset
		// every frame) -- allocating a fresh DynamicUniforms per draw created and freed GPU
		// buffers every frame.
// ColorModulator.a carries the fade alpha (the 1.20.1 chunk fade-in, step 3):
		GpuBufferSlice transforms = alpha >= 1.0F
				? this.ownTransforms.writeTransform(viewMatrix)
				: this.ownTransforms.writeTransform(viewMatrix, new org.joml.Vector4f(1.0F, 1.0F, 1.0F, alpha));

		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		RenderPass pass = encoder.createRenderPass(() -> "simpleclouds", colorView, Optional.empty(), depthView, OptionalDouble.empty());
		pass.setPipeline(this.pipeline);
		RenderSystem.bindDefaultUniforms(pass);
		// Dither matrix: TextureManager.getTexture auto-loads simpleclouds:textures/shader/bayer_matrix.png
		// as a SimpleTexture (same resource the 1.20.1 mod sampled). Declared on the bind group layout
		// AND bound here -- rule 3: an unbound/undeclared sampler is a hard failure.
		AbstractTexture bayer = Minecraft.getInstance().getTextureManager().getTexture(BAYER_TEXTURE);
		pass.bindTexture("BayerMatrixSampler", bayer.getTextureView(), bayer.getSampler());
		pass.setUniform("DynamicTransforms", transforms);
		pass.setUniform("CloudLighting", this.lightingUbo);
		// UseNormals follows the live config (cubeNormals, default off): write the
		// full 16-byte CloudShading block into the per-frame ring slot.
		try (var view = this.useNormalsRing[this.useNormalsRingSlot].slice().map(true, false))
		{
			ByteBuffer data = view.data();
			data.putFloat(0, CLOUD_SHADING_BASE[0]);
			data.putFloat(4, CLOUD_SHADING_BASE[1]);
			data.putFloat(8, CLOUD_SHADING_BASE[2]);
			data.putFloat(12, SimpleCloudsConfig.CLIENT.cubeNormals.get() ? 1.0F : 0.0F);
		}
		pass.setUniform("CloudShading", this.useNormalsRing[this.useNormalsRingSlot]);
		pass.setUniform("CloudFog", this.fogUbo);
		pass.setVertexBuffer(0, this.quadVertexBuffer.slice());
		pass.setVertexBuffer(1, instances.slice());
		pass.setIndexBuffer(this.quadIndexBuffer, IndexType.SHORT);
		pass.drawIndexed(QUAD_INDICES.length, count, 0, 0, 0);
		pass.close();
		this.useNormalsRingSlot = (this.useNormalsRingSlot + 1) % 3;
	}

	/**
	 * Draws the storm fog overlay on top of the scene.
	 * @param intensity 0..1 (CPU-measured storm coverage above the camera)
	 * @param lightningMul reserved for lightning flashes (world effects task)
	 */
	public void drawStormFog(float intensity, float lightningMul)
	{
		if (intensity <= 0.0F)
			return;
		try (var view = this.stormFogUbo.slice().map(true, false))
		{
			ByteBuffer data = view.data();
			data.putFloat(0, 0.04F);
			data.putFloat(4, 0.045F);
			data.putFloat(8, 0.06F);
			data.putFloat(12, Math.min(intensity, 1.0F));
			data.putFloat(16, 0.6F);
			data.putFloat(20, lightningMul);
		}

		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		GpuTextureView colorView = main.getColorTextureView();
		GpuTextureView depthView = main.getDepthTextureView();

		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		RenderPass pass = encoder.createRenderPass(() -> "simpleclouds.stormFog", colorView, Optional.empty(), depthView, OptionalDouble.empty());
		pass.setPipeline(this.stormFogPipeline);
		pass.setUniform("StormFog", this.stormFogUbo);
		pass.setVertexBuffer(0, this.triangleBuffer.slice());
		pass.draw(3, 1, 0, 0);
		pass.close();
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
		RenderPass pass = encoder.createRenderPass(() -> "simpleclouds.skyFlash", colorView, Optional.empty(), depthView, OptionalDouble.empty());
		pass.setPipeline(this.skyFlashPipeline);
		pass.setUniform("SkyFlash", this.skyFlashUbo);
		pass.setVertexBuffer(0, this.triangleBuffer.slice());
		pass.draw(3, 1, 0, 0);
		pass.close();
	}

	/** Draws the transparent cloud edges after the opaque pass (no depth write, blended). */
	private boolean loggedFirstTransparencyDraw = false;

	/** Transparency variant of {@link #drawClouds} for one chunk (A1: persistent
	 *  per-chunk GPU buffer; the fade-in alpha rides on ColorModulator.a). */
	public void drawTransparencyClouds(Matrix4f viewMatrix, GpuBuffer instances, int count, float alpha)
	{
		if (instances == null || count == 0)
			return;
		if (!this.loggedFirstTransparencyDraw)
		{
			this.loggedFirstTransparencyDraw = true;
			LOGGER.info("Simple Clouds clouds: first transparent draw, {} instances", count);
		}

		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		GpuTextureView colorView = main.getColorTextureView();
		GpuTextureView depthView = main.getDepthTextureView();

// ColorModulator.a carries the fade alpha (the 1.20.1 chunk fade-in, step 3):
		GpuBufferSlice transforms = alpha >= 1.0F
				? this.ownTransforms.writeTransform(viewMatrix)
				: this.ownTransforms.writeTransform(viewMatrix, new org.joml.Vector4f(1.0F, 1.0F, 1.0F, alpha));

		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		RenderPass pass = encoder.createRenderPass(() -> "simpleclouds.transparency", colorView, Optional.empty(), depthView, OptionalDouble.empty());
		pass.setPipeline(this.transparencyPipeline);
		RenderSystem.bindDefaultUniforms(pass);
		AbstractTexture bayer = Minecraft.getInstance().getTextureManager().getTexture(BAYER_TEXTURE);
		pass.bindTexture("BayerMatrixSampler", bayer.getTextureView(), bayer.getSampler());
		pass.setUniform("DynamicTransforms", transforms);
		pass.setUniform("CloudShading", this.shadingUbo);
		pass.setUniform("CloudFog", this.fogUbo);
		pass.setVertexBuffer(0, this.quadVertexBuffer.slice());
		pass.setVertexBuffer(1, instances.slice());
		pass.setIndexBuffer(this.quadIndexBuffer, IndexType.SHORT);
		pass.drawIndexed(QUAD_INDICES.length, count, 0, 0, 0);
		pass.close();
	}

	/**
	 * 26.2 previewer: draws the static preview box (instance data in "box space",
	 * center at (0, (BOX_Y0+BOX_Y1)/2, 0)) into the MAIN frame with the screen's
	 * orbit view matrix, no depth test (draws over the world). The orbit view maps
	 * the box center to (0, 0, -distance) in the frame's view space -- i.e.
	 * straight in front of the player -- so no extra model offset is needed.
	 */
	public void drawPreview(Matrix4f orbitView, GpuBuffer instances, int count)
	{
		if (instances == null || count == 0)
			return;
		GpuBufferSlice transforms = this.ownTransforms.writeTransform(orbitView);
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		RenderPass pass = encoder.createRenderPass(() -> "simpleclouds.preview", main.getColorTextureView(),
				Optional.empty(), main.getDepthTextureView(), OptionalDouble.empty());
		pass.setPipeline(this.previewPipeline);
		RenderSystem.bindDefaultUniforms(pass);
		AbstractTexture bayer = Minecraft.getInstance().getTextureManager().getTexture(BAYER_TEXTURE);
		pass.bindTexture("BayerMatrixSampler", bayer.getTextureView(), bayer.getSampler());
		pass.setUniform("DynamicTransforms", transforms);
		pass.setUniform("CloudLighting", this.lightingUbo);
		pass.setUniform("CloudShading", this.shadingUbo);
		pass.setUniform("CloudFog", this.fogUbo);
		pass.setVertexBuffer(0, this.quadVertexBuffer.slice());
		pass.setVertexBuffer(1, instances.slice());
		pass.setIndexBuffer(this.quadIndexBuffer, IndexType.SHORT);
		pass.drawIndexed(QUAD_INDICES.length, count, 0, 0, 0);
		pass.close();
	}

	/**
	 * Renders the cloud instances into the top-down ortho shadow depth target
	 * (cleared to 1.0; a cloud writes its window-space depth per XZ texel).
	 */
	/** One per-chunk instance source (A1: the shadow pass draws the persistent
	 *  per-chunk GPU buffers, one drawIndexed per chunk inside a single pass). */
	public record InstanceSource(GpuBuffer buffer, int count)
	{
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
				-SHADOW_RADIUS, SHADOW_RADIUS, -SHADOW_RADIUS, SHADOW_RADIUS, 0.0F, depthFar);

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
		RenderPass pass = encoder.createRenderPass(() -> "simpleclouds.shadowMap", colorView, Optional.empty(), depthView, OptionalDouble.of(1.0));
		pass.setPipeline(this.shadowPipeline);
		pass.setUniform("ShadowMatrices", matricesBuf);
		pass.setVertexBuffer(0, this.quadVertexBuffer.slice());
		for (InstanceSource source : sources)
		{
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
				-SHADOW_RADIUS, SHADOW_RADIUS, -SHADOW_RADIUS, SHADOW_RADIUS, 0.0F, depthFar);
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

		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		// COLOR-ONLY pass: attaching the main depth view as the pass's depth-stencil
		// attachment AND sampling it as a texture in the same pass is undefined
		// (reads back garbage) — the atmospheric pass (line below) uses this 2-arg
		// overload for exactly this reason.
		RenderPass pass = encoder.createRenderPass(() -> "simpleclouds.terrainShadows", colorView, Optional.empty());
		pass.setPipeline(this.terrainPipeline);
		RenderSystem.bindDefaultUniforms(pass);
		pass.setUniform("DynamicTransforms", this.ownTransforms.writeTransform(viewMatrix));
		pass.setUniform("ShadowPass", terrainBuf);
		pass.bindTexture("DepthSampler", depthView, this.nearestSampler);
		// Step 6: the original multiplies the shadowed scene color by
		// ShadowColorMultiplier, so the pass samples the scene color itself.
		pass.bindTexture("DiffuseSampler", colorView, this.nearestSampler);
		pass.bindTexture("ShadowMap", this.shadowTarget.getDepthTextureView(), this.nearestSampler);
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
		if (vertexCount <= 0)
			return;
		int sections = Math.min(vertexCount / 24, 4096 / 24); // the index buffer covers this many sections
		// Recreation (not resize — the 26.2 GlBuffer has no resize): bolts live at
		// most a couple of seconds, so the churn is bounded and infrequent.
		if (this.lightningVertexBuffer != null)
			this.lightningVertexBuffer.close();
		this.lightningVertexBuffer = this.device.createBuffer(() -> "simpleclouds.lightning", GpuBuffer.USAGE_VERTEX, vertexData);
		var transforms = this.ownTransforms.writeTransform(viewMatrix);
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		GpuTextureView colorView = main.getColorTextureView();
		GpuTextureView depthView = main.getDepthTextureView();
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		RenderPass pass = encoder.createRenderPass(() -> "simpleclouds.lightning", colorView, Optional.empty(), depthView, OptionalDouble.empty());
		pass.setPipeline(this.lightningPipeline);
		RenderSystem.bindDefaultUniforms(pass);
		pass.setUniform("DynamicTransforms", transforms);
		pass.setVertexBuffer(0, this.lightningVertexBuffer.slice());
		pass.setIndexBuffer(this.lightningIndexBuffer, IndexType.SHORT);
		pass.drawIndexed(sections * 36, 1, 0, 0, 0);
		pass.close();
	}

/**
	 * Draws one atmospheric-cloud formation pass over the whole view. The shader
	 * samples the main color target itself (DiffuseSampler) and writes the
	 * composited result back, so this pass must run AFTER everything else that
	 * writes to the scene (the renderer calls it last).
	 *
	 * @param viewMatrix world -&gt; camera
	 * @param transform the formation's scale+wind-yaw matrix (shader's Transform)
	 * @param shift the formation's time offset (shader's ShiftMovement)
	 * @param densityMult 0..1 cross-fade multiplier (transition blending)
	 * @param density the formation's base density
	 * @param fovDeg the level projection's vertical FOV in degrees
	 * @param aspect window aspect (width/height)
	 */
	public void drawAtmosphericClouds(org.joml.Matrix4f viewMatrix, org.joml.Matrix2f transform,
			float shift, float densityMult, float density, float r, float g, float b, float a,
			float fovDeg, float aspect)
	{
		float cloudDensity = density * densityMult;
		if (cloudDensity <= 0.01F)
			return;

		this.atmosphericIdx = (this.atmosphericIdx + 1) % 3;
		GpuBuffer buf = this.atmosphericRing[this.atmosphericIdx];
		try (var view = buf.slice().map(true, false))
		{
			ByteBuffer data = view.data();
			writeMatrix(data, 0, viewMatrix);
			float[] t = new float[4];
			transform.get(t);
			data.putFloat(64, t[0]);  // m00
			data.putFloat(68, t[1]);  // m01
			data.putFloat(72, t[2]);  // m10
			data.putFloat(76, t[3]);  // m11
			data.putFloat(80, 128.0F);              // PixelScale
			data.putFloat(84, 10000.0F);            // SpanX
			data.putFloat(88, 10000.0F);            // SpanZ
			data.putFloat(92, 30000.0F);            // MaxDist
			data.putFloat(96, 10000.0F);            // FadeStart
			data.putFloat(100, shift);              // ShiftMovement
			data.putFloat(104, cloudDensity);       // CloudDensity
			data.putFloat(108, (float) Math.tan(Math.toRadians(fovDeg) / 2.0));
			data.putFloat(112, aspect);
			data.putFloat(120, r);
			data.putFloat(124, g);
			data.putFloat(128, b);
			data.putFloat(132, a);
		}

		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		GpuTextureView colorView = main.getColorTextureView();

		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		// No depth attachment at all: the 3-arg overload (the layer doesn't read
		// or write depth; the pipeline's empty DepthStencilState handles it).
		RenderPass pass = encoder.createRenderPass(() -> "simpleclouds.atmosphericClouds", colorView, Optional.empty());
		pass.setPipeline(this.atmosphericPipeline);
		pass.setUniform("AtmosphericPass", buf);
		pass.bindTexture("DiffuseSampler", colorView, this.nearestSampler);
		pass.setVertexBuffer(0, this.triangleBuffer.slice());
		pass.draw(3, 1, 0, 0);
		pass.close();
	}

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
	private final net.minecraft.client.renderer.DynamicUniforms ownTransforms = new net.minecraft.client.renderer.DynamicUniforms();


	@Override

	public void close()
	{
		// A1: the per-chunk instance GpuBuffers are owned by the renderer's chunk cache
		// (freed there on eviction / shutdown).
		this.triangleBuffer.close();
		this.stormFogUbo.close();
		this.shadowTarget.destroyBuffers();
		for (GpuBuffer b : this.shadowMatricesRing)
			b.close();
		for (GpuBuffer b : this.terrainPassRing)
			b.close();
		for (GpuBuffer b : this.atmosphericRing)
			b.close();
		for (GpuBuffer b : this.useNormalsRing)
			b.close();
		if (this.lightningVertexBuffer != null)
			this.lightningVertexBuffer.close();
		this.lightningIndexBuffer.close();
		this.nearestSampler.close();
		this.quadVertexBuffer.close();
		this.quadIndexBuffer.close();
		this.lightingUbo.close();
		this.shadingUbo.close();
		this.fogUbo.close();
		this.ownTransforms.close();
	}
}

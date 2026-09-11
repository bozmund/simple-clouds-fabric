package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
	private final GpuBuffer fogUbo;
	private GpuBuffer instanceBuffer;
	private int instanceCount = 0;
	private GpuBuffer transparencyInstanceBuffer;
	private int transparencyInstanceCount = 0;
	private final RenderPipeline stormFogPipeline;
	private final GpuBuffer triangleBuffer;
	private final GpuBuffer stormFogUbo;

	// Cloud shadow map (26.2 slice): top-down ortho depth pass over the same
	// instances + a fullscreen terrain-shadow pass. See core/clouds_shadow.* and
	// core/terrain_shadows.* for the shaders and PORTING.md for the slice scope.
	private static final int SHADOW_SIZE = 512; // 1 block/texel over the 512x512 block field
	private static final float SHADOW_RADIUS = 256.0F; // world blocks XZ around the camera (512x512 field on a 256x256 map: 2 blocks/texel)
	private static final float SHADOW_FAR = 600.0F;
	private static final float SHADOW_BIAS = 0.005F;
	private static final float SHADOW_INTENSITY = 0.45F;
	private static final Identifier CLOUDS_SHADOW_LOCATION = SimpleCloudsMod.id("core/clouds_shadow");
	private static final Identifier TERRAIN_SHADOWS_LOCATION = SimpleCloudsMod.id("core/terrain_shadows");

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
	private final GpuBuffer shadowMatricesUbo;
	private final GpuBuffer terrainPassUbo;
	private final GpuSampler nearestSampler;
	private boolean shadowRenderedThisFrame = false;

	public CloudsDrawPipeline()
	{
		GpuDevice device = RenderSystem.getDevice();

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
				.withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN, false))
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
				.withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN, true))
				.build();

		BindGroupLayout terrainBgl = BindGroupLayout.builder()
				.withUniform("ShadowPass", UniformType.UNIFORM_BUFFER)
				.withSampler("DepthSampler")
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

		// std140: two mat4 blocks = 64 bytes each = 128 bytes total.
		this.shadowMatricesUbo = device.createBuffer(() -> "simpleclouds.shadowMatrices", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_READ, 128L);
		this.terrainPassUbo = device.createBuffer(() -> "simpleclouds.terrainShadowPass", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_READ, 80L);
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

	/** Uploads new per-instance data (replacing the previous). A null buffer means "no
	 * instances" (the generation can be empty for a frame) -- keep an empty valid buffer
	 * so the draw's buffer bind stays legal. */
	public void setInstances(ByteBuffer instanceData, int count)
	{
		if (this.instanceBuffer != null)
			this.instanceBuffer.close();
		if (instanceData == null)
		{
			// A zero-byte buffer is rejected by createBuffer; use one dummy float (0
			// instances are drawn, so its content never matters).
			instanceData = ByteBuffer.allocateDirect(4).order(java.nio.ByteOrder.nativeOrder());
			count = 0;
		}
		this.instanceBuffer = RenderSystem.getDevice().createBuffer(() -> "simpleclouds.instances", GpuBuffer.USAGE_VERTEX, instanceData);
		this.instanceCount = count;
	}

	/** Uploads new transparent per-instance data (replacing the previous). */
	public void setTransparencyInstances(ByteBuffer instanceData, int count)
	{
		if (this.transparencyInstanceBuffer != null)
			this.transparencyInstanceBuffer.close();
		this.transparencyInstanceBuffer = instanceData != null
				? RenderSystem.getDevice().createBuffer(() -> "simpleclouds.instancesTransparent", GpuBuffer.USAGE_VERTEX, instanceData)
				: null;
		this.transparencyInstanceCount = count;
	}

	/** Draws the current cloud faces into the main render target. */
	private boolean loggedFirstDraw = false;

	/**
	 * Draws the current cloud faces into the main render target.
	 * @param viewMatrix the world view matrix (R * T(-cameraPos)) — the ModelViewMat for a
	 *                   world at the origin; the model-view stack is already popped by the
	 *                   time our LevelRenderer.render TAIL hook runs.
	 */
	public void draw(Matrix4f viewMatrix)
	{
		if (this.instanceBuffer == null || this.instanceCount == 0)
			return;
		if (!this.loggedFirstDraw)
		{
			this.loggedFirstDraw = true;
			LOGGER.info("Simple Clouds clouds: first draw, {} instances", this.instanceCount);
		}

		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		GpuTextureView colorView = main.getColorTextureView();
		GpuTextureView depthView = main.getDepthTextureView();

		// DynamicTransforms is not covered by bindDefaultUniforms, so bind it explicitly as
		// vanilla CloudRenderer does. Written into vanilla's shared per-frame ring buffer (reset
		// every frame) -- allocating a fresh DynamicUniforms per draw created and freed GPU
		// buffers every frame.
		GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(viewMatrix);

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
		pass.setUniform("CloudShading", this.shadingUbo);
		pass.setUniform("CloudFog", this.fogUbo);
		pass.setVertexBuffer(0, this.quadVertexBuffer.slice());
		pass.setVertexBuffer(1, this.instanceBuffer.slice());
		pass.setIndexBuffer(this.quadIndexBuffer, IndexType.SHORT);
		pass.drawIndexed(QUAD_INDICES.length, this.instanceCount, 0, 0, 0);
		pass.close();
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

	/** Draws the transparent cloud edges after the opaque pass (no depth write, blended). */
	private boolean loggedFirstTransparencyDraw = false;

	public void drawTransparency(Matrix4f viewMatrix)
	{
		if (this.transparencyInstanceBuffer == null || this.transparencyInstanceCount == 0)
			return;
		if (!this.loggedFirstTransparencyDraw)
		{
			this.loggedFirstTransparencyDraw = true;
			LOGGER.info("Simple Clouds clouds: first transparent draw, {} instances", this.transparencyInstanceCount);
		}

		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		GpuTextureView colorView = main.getColorTextureView();
		GpuTextureView depthView = main.getDepthTextureView();

		GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(viewMatrix);

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
		pass.setVertexBuffer(1, this.transparencyInstanceBuffer.slice());
		pass.setIndexBuffer(this.quadIndexBuffer, IndexType.SHORT);
		pass.drawIndexed(QUAD_INDICES.length, this.transparencyInstanceCount, 0, 0, 0);
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
		GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(orbitView);
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
	public void renderCloudShadowMap(double camX, double camZ)
	{
		if (this.instanceBuffer == null || this.instanceCount == 0)
		{
			this.shadowRenderedThisFrame = false;
			return;
		}

		// Top-down ortho view: world (camX, 0, camZ) origin, looking straight down,
		// view x = world X, view y = world Z (north), view z = -world Y.
		org.joml.Matrix4f shadowView = new org.joml.Matrix4f().set(
				1.0F, 0.0F, 0.0F, (float) -camX,
				0.0F, 0.0F, 1.0F, (float) -camZ,
				0.0F, -1.0F, 0.0F, 0.0F,
				0.0F, 0.0F, 0.0F, 1.0F);
		org.joml.Matrix4f shadowProj = new org.joml.Matrix4f().setOrtho(
				-SHADOW_RADIUS, SHADOW_RADIUS, -SHADOW_RADIUS, SHADOW_RADIUS, 0.0F, SHADOW_FAR);

		try (var view = this.shadowMatricesUbo.slice().map(true, false))
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
		pass.setUniform("ShadowMatrices", this.shadowMatricesUbo);
		pass.setVertexBuffer(0, this.quadVertexBuffer.slice());
		pass.setVertexBuffer(1, this.instanceBuffer.slice());
		pass.setIndexBuffer(this.quadIndexBuffer, IndexType.SHORT);
		pass.drawIndexed(QUAD_INDICES.length, this.instanceCount, 0, 0, 0);
		pass.close();
		this.shadowRenderedThisFrame = true;
	}

	/**
	 * Fullscreen terrain cloud-shadow pass: reconstructs world positions from the
	 * scene depth and darkens fragments under the cloud shadow map.
	 */
	public void drawTerrainShadows(Matrix4f viewMatrix, double camX, double camZ)
	{
		if (!this.shadowRenderedThisFrame)
			return;

		org.joml.Matrix4f shadowView = new org.joml.Matrix4f().set(
				1.0F, 0.0F, 0.0F, (float) -camX,
				0.0F, 0.0F, 1.0F, (float) -camZ,
				0.0F, -1.0F, 0.0F, 0.0F,
				0.0F, 0.0F, 0.0F, 1.0F);
		org.joml.Matrix4f shadowProj = new org.joml.Matrix4f().setOrtho(
				-SHADOW_RADIUS, SHADOW_RADIUS, -SHADOW_RADIUS, SHADOW_RADIUS, 0.0F, SHADOW_FAR);
		org.joml.Matrix4f shadowViewProj = (org.joml.Matrix4f) shadowProj.mul(shadowView);

		try (var view = this.terrainPassUbo.slice().map(true, false))
		{
			ByteBuffer data = view.data();
			writeMatrix(data, 0, shadowViewProj);
			data.putFloat(64, SHADOW_BIAS);
			data.putFloat(68, SHADOW_INTENSITY);
		}

		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		GpuTextureView colorView = main.getColorTextureView();
		GpuTextureView depthView = main.getDepthTextureView();

		GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(viewMatrix);
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		// Color-only pass: the scene depth is sampled as a texture, NOT attached as
		// the depth target -- attaching and sampling the same depth image in one pass
		// is a layout hazard and made the samples read back 0 (verified with the
		// blue/magenta on-screen diagnostics, see PORTING.md).
		RenderPass pass = encoder.createRenderPass(() -> "simpleclouds.terrainShadows", colorView, Optional.empty());
		pass.setPipeline(this.terrainPipeline);
		RenderSystem.bindDefaultUniforms(pass);
		pass.setUniform("DynamicTransforms", transforms);
		pass.setUniform("ShadowPass", this.terrainPassUbo);
		pass.bindTexture("DepthSampler", depthView, this.nearestSampler);
		pass.bindTexture("ShadowMap", this.shadowTarget.getDepthTextureView(), this.nearestSampler);
		pass.setVertexBuffer(0, this.triangleBuffer.slice());
		pass.draw(3, 1, 0, 0);
		pass.close();
	}

	/** Writes a column-major joml matrix into a std140 slot at the given byte offset. */
	private static void writeMatrix(ByteBuffer data, int offset, org.joml.Matrix4f matrix)
	{
		float[] m = new float[16];
		matrix.get(m);
		for (int i = 0; i < 16; i++)
			data.putFloat(offset + i * 4, m[i]);
	}

	@Override
	public void close()
	{
		if (this.instanceBuffer != null)
		{
			this.instanceBuffer.close();
			this.instanceBuffer = null;
		}
		if (this.transparencyInstanceBuffer != null)
		{
			this.transparencyInstanceBuffer.close();
			this.transparencyInstanceBuffer = null;
		}
		this.triangleBuffer.close();
		this.stormFogUbo.close();
		this.shadowTarget.destroyBuffers();
		this.shadowMatricesUbo.close();
		this.terrainPassUbo.close();
		this.nearestSampler.close();
		this.quadVertexBuffer.close();
		this.quadIndexBuffer.close();
		this.lightingUbo.close();
		this.shadingUbo.close();
		this.fogUbo.close();
	}
}

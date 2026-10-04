package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;

import dev.nonamecrackers2.simpleclouds.SimpleCloudsMod;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

/**
 * Draws rain/snow batches using world-space vertices from the original
 * PrecipitationQuad equations. Each batch binds its own precipitation texture.
 */
public final class RainDrawPipeline implements AutoCloseable
{
	private static final Logger LOGGER = LogManager.getLogger("simpleclouds/Pipeline");
	private static final Identifier RAIN_LOCATION = SimpleCloudsMod.id("core/rain");

	public record BatchQuad(float[] vertices, int packedLight) {}
	// Per vertex (28 bytes): XYZ, UV and the original packed-light UV2 pair.
	// The attribute names must match the vsh `in` declarations.
	private static final VertexFormat VERTEX_FORMAT = VertexFormat.builder(0)
			.addAttribute("Position", GpuFormat.RGB32_FLOAT)
			.addAttribute("UV0", GpuFormat.RG32_FLOAT)
			.addAttribute("UV2", GpuFormat.RG32_SINT)
			.build();

	private final RenderPipeline pipeline;
	private final RenderPipeline depthWritePipeline;
	private final GpuBuffer alphaUbo;
	// Own DynamicUniforms (not the shared per-frame one): the rain pass is encoded at the
	// LevelRenderer.render TAIL hook, and vanilla resets its shared DynamicUniforms once
	// per frame, which left the rain's ModelViewMat invalid (drops never rendered). The
	// cloud pipeline uses the same private one for the same reason.
	// 26.3 removed net.minecraft.client.renderer.DynamicUniforms (see CloudTransformRing).
	private final CloudTransformRing ownTransforms = new CloudTransformRing("simpleclouds.rainTransforms", 256);
	private GpuBuffer vertexBuffer;
	private GpuBuffer indexBuffer;
	private final GpuSampler lightmapSampler;
	private int dropCount;
	private boolean loggedFirstDraw;
	private static final boolean TEST_SHADER_WEATHER = "1".equals(System.getenv("SIMPLECLOUDS_DEV"));
	private int diagnosticDraws;
	private final java.util.Set<Identifier> diagnosticTextures = new java.util.HashSet<>();
	private float[] diagnosticVertex;

	public RainDrawPipeline()
	{
		GpuDevice device = RenderSystem.getDevice();
		this.pipeline = createPipeline(false);
		this.depthWritePipeline = createPipeline(true);
		// std140 RainPass: alpha at 0, camera vec3 at 16 (block rounded to32).
		this.alphaUbo = device.createBuffer(() -> "simpleclouds.rainAlpha", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_READ, 32L);
		this.lightmapSampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
				FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.empty());
	}

	private static RenderPipeline createPipeline(boolean writeDepth) {
		BindGroupLayout bgl = BindGroupLayout.builder()
				.withUniform("RainPass", UniformType.UNIFORM_BUFFER)
				.withUniform("RainTexture", UniformType.COMBINED_IMAGE_SAMPLER)
				.withUniform("Sampler2", UniformType.COMBINED_IMAGE_SAMPLER)
				.build();
		return RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
				.withLocation(SimpleCloudsMod.id(writeDepth ? "core/rain_depth_write" : "core/rain_depth_read"))
				.withVertexShader(RAIN_LOCATION)
				.withFragmentShader(RAIN_LOCATION)
				.withBindGroupLayout(bgl)
				.withVertexBinding(0, VERTEX_FORMAT)
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false)
				.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
				// 26.3 uses reversed Z: larger depth is closer. LESS_THAN is the
				// wrong comparison here. Depth writes follow the original conditional
				// shader/transparency policy; terrain/roof testing always stays on.
				.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, writeDepth))
				.build();
	}

	/**
	 * Builds a dynamic batch of four XYZUV vertices per precipitation quad.
	 */
	public void setDrops(List<BatchQuad> drops, float alpha)
	{
		if (drops.isEmpty())
		{
			if (this.vertexBuffer != null) this.vertexBuffer.close();
			if (this.indexBuffer != null) this.indexBuffer.close();
			this.vertexBuffer = null;
			this.indexBuffer = null;
			this.dropCount = 0;
			return;
		}

		ByteBuffer data = ByteBuffer.allocateDirect(drops.size() * 4 * 28).order(ByteOrder.LITTLE_ENDIAN);
		for (BatchQuad quad : drops)
		{
			float[] drop = quad.vertices();
			if (drop.length != 20) throw new IllegalArgumentException("Expected four XYZUV vertices");
			for (int vertex = 0; vertex < 4; vertex++) {
				for (int field = 0; field < 5; field++) data.putFloat(drop[vertex * 5 + field]);
				data.putInt(quad.packedLight() & 0xffff);
				data.putInt((quad.packedLight() >>> 16) & 0xffff);
			}
		}
		data.flip();

		ByteBuffer indices = ByteBuffer.allocateDirect(drops.size() * 6 * 2).order(ByteOrder.LITTLE_ENDIAN);
		for (int d = 0; d < drops.size(); d++)
		{
			int v = d * 4;
			indices.putShort((short) v);
			indices.putShort((short) (v + 1));
			indices.putShort((short) (v + 2));
			indices.putShort((short) v);
			indices.putShort((short) (v + 2));
			indices.putShort((short) (v + 3));
		}
		indices.flip();

		if (this.vertexBuffer != null)
			this.vertexBuffer.close();
		if (this.indexBuffer != null)
			this.indexBuffer.close();
		this.vertexBuffer = RenderSystem.getDevice().createBuffer(() -> "simpleclouds.rainVertices", GpuBuffer.USAGE_VERTEX, data);
		this.indexBuffer = RenderSystem.getDevice().createBuffer(() -> "simpleclouds.rainIndices", GpuBuffer.USAGE_INDEX, indices);
		this.dropCount = drops.size();
		if (TEST_SHADER_WEATHER) this.diagnosticVertex = drops.getFirst().vertices().clone();

		try (var view = this.alphaUbo.slice().map(true, false))
		{
			view.data().putFloat(0, alpha);
		}
	}

	/** Draws the rain into the main target. */
	public void draw(Matrix4f viewMatrix, Identifier texture)
	{
		if (this.vertexBuffer == null || this.indexBuffer == null)
			return;
		if (!this.loggedFirstDraw)
		{
			this.loggedFirstDraw = true;
			LOGGER.info("Simple Clouds: first rain draw");
		}

		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		GpuTextureView colorView = main.getColorTextureView();
		GpuTextureView depthView = main.getDepthTextureView();

		this.ownTransforms.reset();
		var cameraPosition = Minecraft.getInstance().gameRenderer.mainCamera().position();
		try (var mapping = this.alphaUbo.slice().map(true, false)) {
			mapping.data().putFloat(16,(float)cameraPosition.x);
			mapping.data().putFloat(20,(float)cameraPosition.y);
			mapping.data().putFloat(24,(float)cameraPosition.z);
		}
		GpuBufferSlice transforms = this.ownTransforms.writeTransform(viewMatrix);
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		// Color-only pass (the scene depth is only tested against, never read).
		boolean improvedTransparency = Minecraft.getInstance().gameRenderer.gameRenderState().optionsRenderState.improvedTransparency;
		boolean shadersRunning = nonamecrackers2.crackerslib.common.compat.CompatHelper.areShadersRunning();
		boolean writeDepth = OriginalPrecipitationDepth.writesDepth(improvedTransparency, shadersRunning);
		var compiledPipeline = RenderSystem.getCompiledPipeline(writeDepth ? this.depthWritePipeline : this.pipeline);
		AbstractTexture rainTexture = Minecraft.getInstance().getTextureManager().getTexture(texture);
		try (RenderPass pass = encoder.createRenderPass(() -> "simpleclouds.rain", colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
		pass.setPipeline(compiledPipeline);
		RenderSystem.bindDefaultUniforms(pass);
		pass.setUniform("DynamicTransforms", transforms);
		pass.setUniform("RainPass", this.alphaUbo);
		// (hoisted above the render pass: 26.3 forbids texture uploads inside one)
		pass.setUniform("RainTexture", rainTexture.getTextureView(), rainTexture.getSampler());
		pass.setUniform("Sampler2", Minecraft.getInstance().gameRenderer.levelLightmap(), this.lightmapSampler);
		pass.setVertexBuffer(0, this.vertexBuffer.slice());
		pass.setIndexBuffer(this.indexBuffer, IndexType.SHORT);
		pass.drawIndexed(this.dropCount * 6, 1, 0, 0, 0);
		}
		// Bounded, opt-in test evidence: reaching this point means the pass was
		// encoded and closed, not that its fragments survived depth testing.
		boolean firstTexture = TEST_SHADER_WEATHER && this.diagnosticTextures.add(texture);
		if (TEST_SHADER_WEATHER && (this.diagnosticDraws++ < 4 || firstTexture)) {
			var projection = Minecraft.getInstance().gameRenderer.gameRenderState()
					.levelRenderState.cameraRenderState.projectionMatrix;
			var clip = new org.joml.Vector4f(this.diagnosticVertex[0], this.diagnosticVertex[1], this.diagnosticVertex[2], 1);
			viewMatrix.transform(clip);
			projection.transform(clip);
			LOGGER.info("[CUSTOM-WEATHER-PASS] encoded=true depthWrite={} improvedTransparency={} shaders={} texture={} quads={} vertex={},{},{} clip={},{},{},{}",
					writeDepth,improvedTransparency,shadersRunning,texture,this.dropCount,this.diagnosticVertex[0],this.diagnosticVertex[1],this.diagnosticVertex[2],
					clip.x,clip.y,clip.z,clip.w);
		}
	}

	@Override
	public void close()
	{
		this.alphaUbo.close();
		this.ownTransforms.close();
		if (this.vertexBuffer != null)
			this.vertexBuffer.close();
		if (this.indexBuffer != null)
			this.indexBuffer.close();
		this.lightmapSampler.close();
	}
}

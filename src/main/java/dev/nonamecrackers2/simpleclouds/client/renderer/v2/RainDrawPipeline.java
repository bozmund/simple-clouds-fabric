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
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;

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

	// Per vertex (20 bytes): world position (3 floats) and UV (2 floats).
	// The attribute names must match the vsh `in` declarations.
	private static final VertexFormat VERTEX_FORMAT = VertexFormat.builder(0)
			.addAttribute("Position", GpuFormat.RGB32_FLOAT)
			.addAttribute("UV0", GpuFormat.RG32_FLOAT)
			.build();

	private final RenderPipeline pipeline;
	private final GpuBuffer alphaUbo;
	// Own DynamicUniforms (not the shared per-frame one): the rain pass is encoded at the
	// LevelRenderer.render TAIL hook, and vanilla resets its shared DynamicUniforms once
	// per frame, which left the rain's ModelViewMat invalid (drops never rendered). The
	// cloud pipeline uses the same private one for the same reason.
	// 26.3 removed net.minecraft.client.renderer.DynamicUniforms (see CloudTransformRing).
	private final CloudTransformRing ownTransforms = new CloudTransformRing("simpleclouds.rainTransforms", 256);
	private GpuBuffer vertexBuffer;
	private GpuBuffer indexBuffer;
	private RenderTarget backdrop;
	private int dropCount;
	private boolean loggedFirstDraw;

	public RainDrawPipeline()
	{
		GpuDevice device = RenderSystem.getDevice();

		BindGroupLayout bgl = BindGroupLayout.builder()
				.withUniform("RainPass", UniformType.UNIFORM_BUFFER)
				.withUniform("RainTexture", UniformType.COMBINED_IMAGE_SAMPLER)
				.withUniform("SceneColor", UniformType.COMBINED_IMAGE_SAMPLER)
				.build();
		this.pipeline = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
				.withLocation(RAIN_LOCATION)
				.withVertexShader(RAIN_LOCATION)
				.withFragmentShader(RAIN_LOCATION)
				.withBindGroupLayout(bgl)
				.withVertexBinding(0, VERTEX_FORMAT)
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false)
				.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
				// 26.3 uses reversed Z: larger depth is closer. LESS_THAN is the
				// wrong comparison here, not evidence that precipitation needs no
				// depth test. Preserve terrain/roof occlusion without writing depth.
				.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
				.build();

		this.alphaUbo = device.createBuffer(() -> "simpleclouds.rainAlpha", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_READ, 4L);
	}

	/** Capture the clouds and terrain once, before either rain or snow changes the
	 * main color target. The fragment shader uses this stable scene for contrast. */
	public void captureBackdrop()
	{
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		if (this.backdrop == null || this.backdrop.width != main.width || this.backdrop.height != main.height)
		{
			if (this.backdrop != null) this.backdrop.destroyBuffers();
			this.backdrop = new TextureTarget("simpleclouds.rainBackdrop", main.width, main.height,
					main.getColorTexture().getFormat(), GpuFormat.D32_FLOAT);
		}
		RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
				main.getColorTexture(), this.backdrop.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
	}

	/**
	 * Builds a dynamic batch of four XYZUV vertices per precipitation quad.
	 */
	public void setDrops(List<float[]> drops, float alpha)
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

		ByteBuffer data = ByteBuffer.allocateDirect(drops.size() * 4 * 5 * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (float[] drop : drops)
		{
			if (drop.length != 20) throw new IllegalArgumentException("Expected four XYZUV vertices");
			for (float value : drop) data.putFloat(value);
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
		GpuBufferSlice transforms = this.ownTransforms.writeTransform(viewMatrix);
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		// Color-only pass (the scene depth is only tested against, never read).
		var compiledPipeline = RenderSystem.getCompiledPipeline(this.pipeline);
		AbstractTexture rainTexture = Minecraft.getInstance().getTextureManager().getTexture(texture);
		try (RenderPass pass = encoder.createRenderPass(() -> "simpleclouds.rain", colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
		pass.setPipeline(compiledPipeline);
		RenderSystem.bindDefaultUniforms(pass);
		pass.setUniform("DynamicTransforms", transforms);
		pass.setUniform("RainPass", this.alphaUbo);
		// (hoisted above the render pass: 26.3 forbids texture uploads inside one)
		pass.setUniform("RainTexture", rainTexture.getTextureView(), rainTexture.getSampler());
		pass.setUniform("SceneColor", this.backdrop.getColorTextureView(), rainTexture.getSampler());
		pass.setVertexBuffer(0, this.vertexBuffer.slice());
		pass.setIndexBuffer(this.indexBuffer, IndexType.SHORT);
		pass.drawIndexed(this.dropCount * 6, 1, 0, 0, 0);
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
		if (this.backdrop != null)
			this.backdrop.destroyBuffers();
	}
}

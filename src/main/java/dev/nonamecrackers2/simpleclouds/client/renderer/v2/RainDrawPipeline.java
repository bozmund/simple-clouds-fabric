package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
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
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;

import dev.nonamecrackers2.simpleclouds.SimpleCloudsMod;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

/**
 * 26.2 rain pass: one camera-facing quad per drop (the 1.20.1 PrecipitationQuad
 * renderer, without wind tilt). The vertex buffer is rebuilt every frame from
 * the WorldEffects drop list (a few hundred 32-byte vertices -- trivial).
 */
public final class RainDrawPipeline implements AutoCloseable
{
	private static final Logger LOGGER = LogManager.getLogger("simpleclouds/Pipeline");
	private static final Identifier RAIN_LOCATION = SimpleCloudsMod.id("core/rain");
	private static final Identifier RAIN_TEXTURE_ID = Identifier.fromNamespaceAndPath("minecraft", "textures/environment/rain.png");

	// Per vertex (32 bytes): DropPos(3) + Corner(1) + QuadV(1) + Length(1) + Width(1) + UVOffset(1).
	// The attribute names must match the vsh `in` declarations.
	private static final VertexFormat VERTEX_FORMAT = VertexFormat.builder(0)
			.addAttribute("DropPos", GpuFormat.RGB32_FLOAT)
			.addAttribute("Corner", GpuFormat.R32_FLOAT)
			.addAttribute("QuadV", GpuFormat.R32_FLOAT)
			.addAttribute("Length", GpuFormat.R32_FLOAT)
			.addAttribute("Width", GpuFormat.R32_FLOAT)
			.addAttribute("UVOffset", GpuFormat.R32_FLOAT)
			.build();

	private final RenderPipeline pipeline;
	private final GpuBuffer alphaUbo;
	// Own DynamicUniforms (not the shared per-frame one): the rain pass is encoded at the
	// LevelRenderer.render TAIL hook, and vanilla resets its shared DynamicUniforms once
	// per frame, which left the rain's ModelViewMat invalid (drops never rendered). The
	// cloud pipeline uses the same private one for the same reason.
	private final net.minecraft.client.renderer.DynamicUniforms ownTransforms = new net.minecraft.client.renderer.DynamicUniforms();
	private GpuBuffer vertexBuffer;
	private GpuBuffer indexBuffer;
	private int dropCount;
	private boolean loggedFirstDraw;

	public RainDrawPipeline()
	{
		GpuDevice device = RenderSystem.getDevice();

		BindGroupLayout bgl = BindGroupLayout.builder()
				.withUniform("RainPass", UniformType.UNIFORM_BUFFER)
				.withSampler("RainTexture")
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
				// Step 5 (original parity): no depth test (ALWAYS_PASS) so the rain is drawn
				// on top of the scene (the storm base / sky), like the reference's rain curtain.
				// The LESS_THAN test occluded the sky drops (they sit behind the storm base from
				// the camera's view), leaving only the ground-level drops visible.
				.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
				.build();

		this.alphaUbo = device.createBuffer(() -> "simpleclouds.rainAlpha", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_READ, 4L);
	}

	/**
	 * Builds the dynamic vertex/index buffers for one frame. Each drop is a quad:
	 * vertices (Corner, QuadV) = (-0.5,0), (0.5,0), (0.5,1), (-0.5,1).
	 */
	public void setDrops(List<WorldEffectsDrop> drops, float alpha)
	{
		if (drops.isEmpty())
		{
			this.vertexBuffer = null;
			this.indexBuffer = null;
			return;
		}

		ByteBuffer data = ByteBuffer.allocateDirect(drops.size() * 4 * 8 * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (WorldEffectsDrop drop : drops)
		{
			writeDropVertex(data, drop, -0.5F, 0.0F);
			writeDropVertex(data, drop, 0.5F, 0.0F);
			writeDropVertex(data, drop, 0.5F, 1.0F);
			writeDropVertex(data, drop, -0.5F, 1.0F);
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

	private static void writeDropVertex(ByteBuffer data, WorldEffectsDrop drop, float corner, float quadV)
	{
		data.putFloat(drop.x());
		data.putFloat(drop.y());
		data.putFloat(drop.z());
		data.putFloat(corner);
		data.putFloat(quadV);
		data.putFloat(drop.length());
		data.putFloat(drop.width());
		data.putFloat(drop.uvOffset() + quadV * drop.length() * 0.1F);
	}

	/** Draws the rain into the main target. */
	public void draw(Matrix4f viewMatrix)
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
		RenderPass pass = encoder.createRenderPass(() -> "simpleclouds.rain", colorView, Optional.empty(), depthView, OptionalDouble.empty());
		pass.setPipeline(this.pipeline);
		RenderSystem.bindDefaultUniforms(pass);
		pass.setUniform("DynamicTransforms", transforms);
		pass.setUniform("RainPass", this.alphaUbo);
		TextureManager textureManager = Minecraft.getInstance().getTextureManager();
		AbstractTexture rainTexture = textureManager.getTexture(RAIN_TEXTURE_ID);
		pass.bindTexture("RainTexture", rainTexture.getTextureView(), rainTexture.getSampler());
		pass.setVertexBuffer(0, this.vertexBuffer.slice());
		pass.setIndexBuffer(this.indexBuffer, IndexType.SHORT);
		pass.drawIndexed(this.dropCount * 6, 1, 0, 0, 0);
		pass.close();
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
	}
}

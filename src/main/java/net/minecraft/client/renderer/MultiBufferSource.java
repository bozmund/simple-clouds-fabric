package net.minecraft.client.renderer;

/**
 * Fabric 26.2 compatibility: MultiBufferSource was removed in 26.2.
 * This is a minimal replacement to allow old rendering code to compile.
 */
public class MultiBufferSource
{
	public static MultiBufferSource get(net.minecraft.client.Minecraft minecraft)
	{
		return new MultiBufferSource();
	}

	public com.mojang.blaze3d.vertex.VertexConsumer getBuffer(com.mojang.blaze3d.pipeline.RenderPipeline pipeline)
	{
		return new com.mojang.blaze3d.vertex.BufferBuilder(
			new com.mojang.blaze3d.vertex.ByteBufferBuilder(1024),
			com.mojang.blaze3d.PrimitiveTopology.QUADS,
			com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION
		);
	}
}

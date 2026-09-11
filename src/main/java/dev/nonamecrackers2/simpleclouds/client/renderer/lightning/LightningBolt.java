package dev.nonamecrackers2.simpleclouds.client.renderer.lightning;

import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.util.RandomSource;

/**
 * 26.2 vertical-slice stub. Lightning rendering is not ported yet; bolts tick to death
 * immediately and render nothing. Keeps the public API so WorldEffects and callers
 * compile. Port incrementally.
 */
public class LightningBolt
{
	private final Vector3f position;
	private final float r;
	private final float g;
	private final float b;
	private int age;
	private final int maxAge;

	public LightningBolt(RandomSource random, Vector3f position, int depth, int branchCount, float maxBranchLength, float maxWidth, float minimumPitch, float maximumPitch, float r, float g, float b)
	{
		this.position = position;
		this.r = r;
		this.g = g;
		this.b = b;
		this.maxAge = 20;
		this.age = 0;
	}

	public void tick()
	{
		this.age++;
	}

	public boolean isDead()
	{
		return this.age >= this.maxAge;
	}

	public void render(PoseStack stack, VertexConsumer consumer, float partialTick, float r, float g, float b, float a)
	{
		// TODO(26.2): port lightning line rendering.
	}

	public Vector3f getPosition()
	{
		return this.position;
	}

	public float getFade(float partialTick)
	{
		return Math.max(0.0F, 1.0F - (this.age + partialTick) / (float)this.maxAge);
	}
}

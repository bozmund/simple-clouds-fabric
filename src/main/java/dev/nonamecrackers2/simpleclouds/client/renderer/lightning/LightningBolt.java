package dev.nonamecrackers2.simpleclouds.client.renderer.lightning;

import java.util.List;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * Port of the 1.20.1 jagged lightning bolt (recursive branch tree with per-branch
 * pitch/yaw/width/length, width tapering with depth, per-tick branch restructuring
 * while visible).
 *
 * 26.2 difference: instead of a PoseStack + VertexConsumer (gone), {@link
 * #renderInto(float[], float[], float)} walks the tree with joml matrices and
 * writes world-space positions + colors straight into float arrays; the caller
 * uploads them to a dynamic vertex buffer.
 */
public class LightningBolt
{
	private static final int TOTAL_TIME = 60;
	private static final int STAY_DURATION = 10;
	private static final int FADE_IN_TIME = 2;
	private static final int FADE_OUT_TIME = 20;
	private static final float FLASH_INTENSITY = 2.0F;
	private static final float RESTRUCTURE_FADE_LIMIT = 0.1F;
	private static final int BRANCH_SEQUENCE_FADE_DURATION = 5;
	public static final int MAX_DEPTH = 16;
	public static final int MAX_BRANCHES = 8;
	public static final float MINIMUM_PITCH_ALLOWED = 0.0F;
	public static final float MAXIMUM_PITCH_ALLOWED = 180.0F;
	private static final int VERTS_PER_SECTION = 24; // 6 faces x 4 corners
	private static final int FLOATS_PER_VERTEX = 7;  // position(3) + color(4)

	private final RandomSource random;
	private final int totalDepth;
	private final int branchCount;
	private final float maxBranchLength;
	private final float maxWidth;
	private final float maxPitch;
	private final float minPitch;
	private final Vector3f position;
	private List<Branch> root;
	private int tickCount;
	private float fade;
	private float fadeO;
	private final float r;
	private final float g;
	private final float b;

	public LightningBolt(RandomSource random, Vector3f position, int depth, int branchCount, float maxBranchLength, float maxWidth, float minimumPitch, float maximumPitch, float r, float g, float b)
	{
		this.random = random;
		this.totalDepth = Mth.clamp(depth, 1, MAX_DEPTH);
		this.branchCount = Mth.clamp(branchCount, 1, MAX_BRANCHES);
		this.maxBranchLength = maxBranchLength;
		this.maxWidth = maxWidth;
		this.minPitch = Mth.clamp(minimumPitch, MINIMUM_PITCH_ALLOWED, MAXIMUM_PITCH_ALLOWED);
		this.maxPitch = Mth.clamp(maximumPitch, minimumPitch, MAXIMUM_PITCH_ALLOWED);
		this.position = position;
		this.root = buildBranchesWithChildren(random, depth, 0, branchCount, maxBranchLength, maxWidth, maxWidth, maximumPitch, minimumPitch);
		this.r = r;
		this.g = g;
		this.b = b;
	}

	private static float calculateWidthAtDepth(int maxDepth, int desiredDepth, float maxWidth)
	{
		float width = maxWidth;
		for (int i = 0; i < desiredDepth; i++)
		{
			if (width <= 0.5F)
				return 0.5F;
			width = width - maxWidth / (float) (maxDepth + 1);
		}
		return width;
	}

	private static List<Branch> buildBranchesWithChildren(RandomSource random, int totalDepth, int currentDepth, int branchCount, float maxBranchLength, float maxWidth, float width, float minPitch, float maxPitch)
	{
		if (currentDepth >= totalDepth)
			return List.of();
		var branches = new java.util.ArrayList<Branch>();
		for (int i = 0; i < branchCount; i++)
		{
			float pitch = (maxPitch - minPitch) * random.nextFloat() + minPitch;
			float yaw = 360.0F * random.nextFloat();
			float length = maxBranchLength / 4.0F + maxBranchLength * random.nextFloat();
			float nextWidth = Math.max(0.5F, width - maxWidth / (float) (totalDepth + 1));
			int range = Mth.floor((float) totalDepth - 1.0F / (float) totalDepth * ((float) currentDepth * (float) currentDepth));
			int nextBranchCount = range <= 0 ? 0 : Math.min(random.nextInt(range), branchCount);
			if ((float) currentDepth / (float) totalDepth < 0.5F)
				nextBranchCount = Math.max(nextBranchCount, 1);
			List<Branch> children = buildBranchesWithChildren(random, totalDepth, currentDepth + 1, nextBranchCount, maxBranchLength, maxWidth, nextWidth, minPitch, maxPitch);
			branches.add(new Branch(children, pitch, yaw, width, length));
		}
		return branches;
	}

	private static List<Branch> getBranchesAtDepth(List<Branch> root, int atDepth, int currentDepth)
	{
		if (currentDepth == atDepth)
			return root;
		for (Branch branch : root)
		{
			var list = getBranchesAtDepth(branch.branches, atDepth, currentDepth + 1);
			if (list != null)
				return list;
		}
		return null;
	}

	private List<Branch> getBranchesAtDepth(int depth)
	{
		var branches = getBranchesAtDepth(this.root, depth, 0);
		return branches == null ? List.of() : branches;
	}

	public void tick()
	{
		this.tickCount++;
		if (this.tickCount < TOTAL_TIME)
		{
			this.fadeO = this.fade;
			this.fade = 1.0F;
			if (this.tickCount < STAY_DURATION)
				this.fade = Math.min(1.0F, (float) this.tickCount / (float) FADE_IN_TIME);
			else
				this.fade = Math.max(0.0F, 1.0F - (float) (this.tickCount - STAY_DURATION) / (float) FADE_OUT_TIME);
			this.fade = this.fade * (float) Math.pow((double) this.random.nextFloat(), FLASH_INTENSITY);
			if (this.fade > RESTRUCTURE_FADE_LIMIT)
			{
				int maxDepth = this.totalDepth - Mth.floor((float) this.totalDepth * ((float) this.tickCount / (float) TOTAL_TIME));
				int depth = this.totalDepth - (maxDepth <= 1 ? 0 : this.random.nextInt(maxDepth));
				float width = calculateWidthAtDepth(this.totalDepth, depth, this.maxWidth);
				List<Branch> branches = this.getBranchesAtDepth(depth);
				if (!branches.isEmpty())
				{
					int index = branches.size() <= 1 ? 0 : this.random.nextInt(branches.size());
					Branch branch = branches.get(index);
					branch.setBranches(buildBranchesWithChildren(this.random, this.totalDepth - depth, 0, this.branchCount, this.maxBranchLength, this.maxWidth, width, this.minPitch, this.maxPitch));
				}
			}
		}
	}

	public boolean isDead()
	{
		return this.tickCount > TOTAL_TIME;
	}

	/**
	 * Writes world-space quads (24 verts per branch section set: 6 faces x 4 layers,
	 * each 4 corners) into {@code out} starting at 0; returns the number of FLOATS
	 * written (count = written / 7). Positions are absolute world coordinates; the
	 * caller's view matrix does the camera transform.
	 */
	public int renderInto(float[] out, float partialTick, float r, float g, float b, float a)
	{
		float alpha = Mth.lerp(partialTick, this.fadeO, this.fade) * a;
		if (alpha <= 0.01F)
			return 0;

		int written = 0;
		float animFactor = ((float) this.tickCount + partialTick) / (float) BRANCH_SEQUENCE_FADE_DURATION;
		int maxRenderDepth = Mth.floor((float) this.totalDepth * animFactor);
		// World-space: the bolt's origin is at `position`; the caller's view matrix does the camera transform.
		Matrix4f camera = new Matrix4f().translate(this.position.x, this.position.y, this.position.z);
		for (Branch branch : this.root)
			written += renderBranch(maxRenderDepth, 0, new Vector3f(), camera, out, written, r * this.r, g * this.g, b * this.b, alpha, branch);
		return written;
	}

	private int renderBranch(int maxDepth, int currentDepth, Vector3f offset, Matrix4f mat, float[] out, int offsetI, float r, float g, float b, float a, Branch branch)
	{
		if (currentDepth > maxDepth)
			return 0;
		int written = 0;
		Matrix4f local = new Matrix4f(mat);
		local.translate(offset.x, offset.y, offset.z);
		local.rotate((float) Math.toRadians(branch.yaw), 0.0F, 1.0F, 0.0F);
		local.rotate((float) Math.toRadians(branch.pitch), 1.0F, 0.0F, 0.0F);

		int layers = 4;
		for (int i = 0; i < layers; i++)
		{
			float factor = (float) i / (float) layers;
			float width = branch.width - 4.0F * (branch.width / 4.0F) * factor;
			if (width <= 0.05F)
				continue;
			float length = branch.length - factor;
			float startingY = -factor * 0.5F;
			float alpha = (float) (i + 1) / (float) layers * 0.5F;
			written += lightningBoltSection(local, out, offsetI + written, startingY, width, length, r, g, b, alpha * a);
		}

		float yawRadians = branch.yaw * ((float) Math.PI / 180.0F);
		float pitchRadians = (90.0F - branch.pitch) * ((float) Math.PI / 180.0F);
		float pitchCos = Mth.cos(pitchRadians);
		Vector3f end = new Vector3f(Mth.sin(yawRadians) * pitchCos, Mth.sin(pitchRadians), Mth.cos(yawRadians) * pitchCos)
				.mul(-branch.length).add(offset);
		for (Branch child : branch.branches)
			written += renderBranch(maxDepth, currentDepth + 1, end, local, out, offsetI + written, r, g, b, a, child);
		return written;
	}

	private static int lightningBoltSection(Matrix4f poseMatrix, float[] out, int offsetI, float yStart, float width, float length, float r, float g, float b, float a)
	{
		float halfWidth = width / 2.0F;
		int o = offsetI;
		// six faces, same winding as the 1.20.1 original
		float[][] corners = {
			{ +halfWidth, yStart, -halfWidth }, { -halfWidth, yStart, -halfWidth }, { -halfWidth, yStart - length, -halfWidth }, { +halfWidth, yStart - length, -halfWidth },
			{ +halfWidth, yStart - length, halfWidth }, { -halfWidth, yStart - length, halfWidth }, { -halfWidth, yStart, halfWidth }, { +halfWidth, yStart, halfWidth },
			{ -halfWidth, yStart - length, halfWidth }, { -halfWidth, yStart - length, -halfWidth }, { -halfWidth, yStart, -halfWidth }, { -halfWidth, yStart, halfWidth },
			{ halfWidth, yStart, halfWidth }, { halfWidth, yStart, -halfWidth }, { halfWidth, yStart - length, -halfWidth }, { halfWidth, yStart - length, halfWidth },
			{ -halfWidth, yStart - length, halfWidth }, { halfWidth, yStart - length, halfWidth }, { halfWidth, yStart - length, -halfWidth }, { -halfWidth, yStart - length, -halfWidth },
			{ -halfWidth, yStart, -halfWidth }, { halfWidth, yStart, -halfWidth }, { halfWidth, yStart, halfWidth }, { -halfWidth, yStart, halfWidth }
		};
		var v = new org.joml.Vector4f();
		for (float[] c : corners)
		{
			v.set(c[0], c[1], c[2], 1.0F);
			poseMatrix.transform(v);
			out[o++] = v.x;
			out[o++] = v.y;
			out[o++] = v.z;
			out[o++] = r;
			out[o++] = g;
			out[o++] = b;
			out[o++] = a;
		}
		return VERTS_PER_SECTION * FLOATS_PER_VERTEX;
	}

	public Vector3f getPosition()
	{
		return this.position;
	}

	public float getFade(float partialTick)
	{
		return Mth.lerp(partialTick, this.fadeO, this.fade);
	}

	public static class Branch
	{
		private List<Branch> branches;
		private final float pitch;
		private final float yaw;
		private final float width;
		private final float length;

		public Branch(List<Branch> branches, float pitch, float yaw, float width, float length)
		{
			this.branches = branches;
			this.pitch = pitch;
			this.yaw = yaw;
			this.width = width;
			this.length = length;
		}

		public void setBranches(List<Branch> branches)
		{
			this.branches = branches;
		}
	}
}

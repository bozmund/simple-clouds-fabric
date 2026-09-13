package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;

import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;

/**
 * 26.2 3D cloud generator previewer (mesh + camera math).
 *
 * Renders a static box of cloud instances (generated once by {@link CpuCloudGenerator}
 * around the world origin) with a screen-controlled orbit camera.
 *
 * HOW IT IS DRAWN: into the MAIN frame via {@link CloudsDrawPipeline#drawPreview}
 * (snippet pipeline + DynamicTransforms, no depth test, box model-offset to the
 * player) in the world phase.
 *
 * KEY 26.2 FINDING (why not offscreen): standalone-encoder passes never produce
 * COLOR fragments into a mod-created offscreen target in 26.2 -- the pass clear
 * applies but draws are silently discarded (verified across self-contained and
 * snippet pipelines, RenderTarget and plain GpuTexture targets, world and GUI
 * phases; depth-only writes do work -- that's how the shadow map renders). The
 * only proven color path is the main-frame + snippet pipeline combination, which
 * is also how the main clouds render. The Picture-in-Picture renderer avoids
 * this by re-rendering the world with the frame graph output redirected
 * (RenderSystem.outputColorTextureOverride) -- the proper long-term fix is a
 * frame-graph pass, tracked in PORTING.md.
 *
 * DEVIATIONS from 1.20.1:
 * <ul>
 *   <li>Only the first data-driven cloud type is previewed (the 1.20.1
 *       per-type generator + type selector are not ported).</li>
 *   <li>The preview box is drawn over the live world (no isolated sky-blue
 *       background) at the player position, with an orbit camera
 *       (perspective projection; 1.20.1 used orthographic).</li>
 *   <li>The offscreen "render preview image" export (CloudImageRenderer) is not
 *       ported.</li>
 * </ul>
 */
public class PreviewDrawPipeline implements AutoCloseable
{
	private static final Logger LOGGER = LogManager.getLogger("simpleclouds/Preview");

	// Preview box: 64x64 in XZ around the origin, Y in the usual cloud band.
	public static final int BOX = 32;
	public static final int BOX_Y0 = 16;
	public static final int BOX_Y1 = 80;
	// Box center (used for the camera orbit + the model offset).
	public static final Vector3f BOX_CENTER = new Vector3f(0.0F, (BOX_Y0 + BOX_Y1) / 2.0F, 0.0F);
	// Base camera distance for the preview view; zoom divides it.
	private static final float BASE_DISTANCE = 110.0F;

	private GpuBuffer instanceBuffer;
	private int instanceCount = 0;
	private boolean loggedFirstDraw = false;

	/**
	 * Generates the preview instance mesh once (static; no scrolling in the preview).
	 * Uses the first data-driven cloud type.
	 */
	public void generateMesh()
	{
		List<CpuCloudGenerator.CloudLayerGroup> groups = SimpleCloudsRenderer.dataDrivenGroups();
		if (groups.isEmpty())
			return;
		CpuCloudGenerator generator = new CpuCloudGenerator(List.of(groups.get(0)));
		float[] outOpaque = new float[1];
		float[] outTransparent = new float[1];
		float[] outStorm = new float[1];
		// worldBaseY = 0: the preview screen renders in its own box-local space, not
		// anchored at the world cloudHeight.
		ByteBuffer[] buffers = generator.generate(-BOX, BOX_Y0, -BOX, BOX, BOX_Y1, BOX, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1, 0.0F,
				outOpaque, outTransparent, BOX_Y0, outStorm);
		if (buffers == null || buffers.length == 0 || buffers[0] == null || outOpaque[0] <= 0.0F)
			return;
		if (this.instanceBuffer != null)
			this.instanceBuffer.close();
		this.instanceBuffer = RenderSystem.getDevice().createBuffer(() -> "simpleclouds.preview.instances", GpuBuffer.USAGE_VERTEX, buffers[0]);
		this.instanceCount = (int) outOpaque[0];
		LOGGER.info("Simple Clouds preview: {} preview instances", this.instanceCount);
	}

	/**
	 * Builds the preview orbit view matrix from the Screen3D camera state.
	 *
	 * 1.20.1 used the pose S(zoom)*T(0,0,far/2)*Rx(camRotX)*Ry(pi+camRotY)*T(offset)
	 * under an orthographic GUI projection. The 26.2 preview orbits the box with
	 * the same camera angles under the game's perspective projection:
	 * T(0,0,-distance) * Rx * Ry * T(offset - boxCenter).
	 */
	public static Matrix4f previewViewMatrix(float camRotX, float camRotY, float zoom, Vector3f offset)
	{
		float radX = (float) Math.toRadians(camRotX);
		float radY = (float) Math.toRadians(Math.PI + camRotY);
		Matrix4f view = new Matrix4f().identity();
		view.translate(0.0F, 0.0F, -BASE_DISTANCE / Math.max(zoom, 0.05F));
		view.rotate(radX, 1.0F, 0.0F, 0.0F);
		view.rotate(radY, 0.0F, 1.0F, 0.0F);
		// Screen3D pans by adding to `offset`; the previewer screen initializes it to
		// the box center, so offset-boxCenter is the net pan.
		view.translate(offset.x - BOX_CENTER.x, offset.y - BOX_CENTER.y, offset.z - BOX_CENTER.z);
		return view;
	}

	/** Draws the current box with the given orbit view (world phase, main frame). */
	public void draw(CloudsDrawPipeline pipeline, Matrix4f orbitView)
	{
		if (this.instanceBuffer == null || this.instanceCount == 0)
			return;
		if (!this.loggedFirstDraw)
		{
			this.loggedFirstDraw = true;
			// dev-relaunch.sh waits for the "first draw" marker.
			LOGGER.info("Simple Clouds preview: first draw, {} instances", this.instanceCount);
		}
		pipeline.drawPreview(orbitView, this.instanceBuffer, this.instanceCount);
	}

	public int instanceCount()
	{
		return this.instanceCount;
	}

	@Override
	public void close()
	{
		if (this.instanceBuffer != null)
		{
			this.instanceBuffer.close();
			this.instanceBuffer = null;
		}
	}
}

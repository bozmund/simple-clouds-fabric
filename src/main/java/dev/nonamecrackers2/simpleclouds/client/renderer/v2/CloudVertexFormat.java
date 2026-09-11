package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

/**
 * Vertical-slice (26.2) cloud vertex formats.
 *
 * Two vertex buffers are used:
 * <ul>
 *   <li>Buffer 0 ({@link #QUAD_FORMAT}): per-vertex base quad corner {@code Position}
 *       (step rate 0).</li>
 *   <li>Buffer 1 ({@link #INSTANCE_FORMAT}): per-instance cloud face data
 *       {@code Side}/{@code SidePos}/{@code Radius}/{@code Brightness} (step rate 1).
 *       This replaces the old SSBO {@code SideInfo} (sides.data[gl_InstanceID]).</li>
 * </ul>
 */
public final class CloudVertexFormat
{
	public static final String POSITION = "Position";
	public static final String SIDE = "Side";
	public static final String SIDE_POS = "SidePos";
	public static final String RADIUS = "Radius";
	public static final String BRIGHTNESS = "Brightness";
	public static final String ALPHA = "Alpha";

	/** Per-vertex base quad (buffer 0). */
	public static final VertexFormat QUAD_FORMAT = VertexFormat.builder(0)
			.addAttribute(POSITION, GpuFormat.RGB32_FLOAT)
			.build();

	/** Per-instance cloud face data (buffer 1). */
	public static final VertexFormat INSTANCE_FORMAT = VertexFormat.builder(1)
			.addAttribute(SIDE, GpuFormat.R32_FLOAT)
			.addAttribute(SIDE_POS, GpuFormat.RGB32_FLOAT)
			.addAttribute(RADIUS, GpuFormat.R32_FLOAT)
			.addAttribute(BRIGHTNESS, GpuFormat.R32_FLOAT)
			.build();

	/** Per-instance transparent cloud face data (buffer 1, step rate 1). */
	public static final VertexFormat INSTANCE_ALPHA_FORMAT = VertexFormat.builder(1)
			.addAttribute(SIDE, GpuFormat.R32_FLOAT)
			.addAttribute(SIDE_POS, GpuFormat.RGB32_FLOAT)
			.addAttribute(RADIUS, GpuFormat.R32_FLOAT)
			.addAttribute(BRIGHTNESS, GpuFormat.R32_FLOAT)
			.addAttribute(ALPHA, GpuFormat.R32_FLOAT)
			.build();

	/** Bytes per instance: Side(4) + SidePos(12) + Radius(4) + Brightness(4) = 24. */
	public static final int BYTES_PER_INSTANCE = 24;

	/** Bytes per transparent instance: 24 + Alpha(4) = 28. */
	public static final int BYTES_PER_INSTANCE_ALPHA = 28;

	private CloudVertexFormat()
	{
	}
}

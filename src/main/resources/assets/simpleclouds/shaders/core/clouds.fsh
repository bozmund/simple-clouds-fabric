#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

// Declared here (the device does NOT inject sampler declarations from the bind group
// layout -- 26.2 GlProgram compiles the source as written; the BGL entry only makes the
// name bindable), and bound in CloudsDrawPipeline.draw() via RenderPass.bindTexture.
uniform sampler2D BayerMatrixSampler;

// Edge dithering: fade comes from ColorModulator.a (per-chunk alpha, as in the 1.20.1
// mod -- RenderSystem.setShaderColor(..., chunk.getAlpha(partialTick)) wrote it into the
// transform). With the current solid vertical slice the transform carries alpha 1.0, so
// the discard never fires; it activates once per-face/per-chunk alpha is supplied.
// BayerMatrixSampler is declared on the bind group layout AND bound in draw() with the
// 16x16 bayer_matrix.png -- an unbound sampler would be undefined behavior.

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in float fogDistance;

layout(location = 0) out vec4 fragColor;

layout(std140) uniform CloudFog {
	vec4 FogColor;
	float FogStart;
	float FogEnd;
	float DitherScale;
};

void main()
{
	float fade = ColorModulator.a;
	float r = texture(BayerMatrixSampler, gl_FragCoord.xy * DitherScale).r;
	// Negative values below -2 encode the complementary mask of a departing
	// chunk: each Bayer cell shows either the old or new voxel, not both.
	if (fade <= -2.0) {
		if (r < -fade - 2.0) discard;
	} else if (fade < r) discard;

	vec4 color = vertexColor * vec4(ColorModulator.rgb, 1.0);
	color = mix(color, FogColor, smoothstep(FogStart, FogEnd, fogDistance));

	fragColor = vec4(color.rgb, 1.0);
}

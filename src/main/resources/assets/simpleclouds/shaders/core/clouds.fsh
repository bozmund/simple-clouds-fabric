#version 330

#moj_import <minecraft:dynamictransforms.glsl>

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

in vec4 vertexColor;
in float fogDistance;

out vec4 fragColor;

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
	if (fade < r)
		discard;

	vec4 color = vertexColor * vec4(ColorModulator.rgb, 1.0);
	color = mix(color, FogColor, smoothstep(FogStart, FogEnd, fogDistance));

	fragColor = vec4(color.rgb, 1.0);
}

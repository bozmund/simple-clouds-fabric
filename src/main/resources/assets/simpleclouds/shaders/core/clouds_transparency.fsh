#version 330

#moj_import <minecraft:dynamictransforms.glsl>

// 26.2 port of clouds_transparency.fsh. The 1.20.1 original wrote weighted-blended
// order-independent transparency into two color attachments (accumColor/revealage,
// per the JCGT weighted blended order paper) which a separate composite pass then
// divided out. The 26.2 slice draws into the main target with standard alpha
// blending instead (deviation: no order independence; visually equivalent for the
// thin single-layer edges this pass handles).
//
// BayerMatrixSampler is declared here (the device does not inject sampler
// declarations), declared on the bind group layout, and bound in
// CloudsDrawPipeline.drawTransparency().
uniform sampler2D BayerMatrixSampler;

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

	fragColor = vec4(color.rgb, color.a);
}

#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

// Original 1.20.1 weighted-blended OIT equations, with modern uniform bindings.
//
// BayerMatrixSampler is declared here (the device does not inject sampler
// declarations), declared on the bind group layout, and bound in
// CloudsDrawPipeline.drawTransparency().
uniform sampler2D BayerMatrixSampler;

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in float fogDistance;
layout(location = 2) in float vertexDistance;

#if defined(ORIGINAL_REVEALAGE_ONLY)
layout(location = 0) out float revealage;
#elif defined(ORIGINAL_ACCUM_ONLY)
layout(location = 0) out vec4 accumColor;
#else
layout(location = 0) out vec4 accumColor;
layout(location = 1) out float revealage;
#endif

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
	if (fade <= -2.0) {
		if (r < -fade - 2.0) discard;
	} else if (fade < r) discard;

	vec4 color = vertexColor * vec4(ColorModulator.rgb, 1.0);
	color = mix(color, FogColor, smoothstep(FogStart, FogEnd, fogDistance));

	vec4 premul = vec4(color.rgb * color.a, color.a);
	float z = min(vertexDistance / 1000.0, 1.0);
	float weight = max(premul.a * 3000.0 * pow(1.0 - z, 3.0), 0.01);
#ifndef ORIGINAL_REVEALAGE_ONLY
	accumColor = premul * weight;
#endif
#ifndef ORIGINAL_ACCUM_ONLY
	revealage = premul.a;
#endif
}

#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:fog.glsl>

// Custom rain fragment: the vanilla rain streak texture (white on transparent),
// alpha-blended. A narrow center u-strip per drop (as in the 1.20.1 quad: the
// width maps to u 0.5+-width/4) keeps individual streaks thin.
uniform sampler2D RainTexture;

layout(std140) uniform RainPass {
	float RainAlpha;
	vec3 CameraPosition;
};

layout(location = 0) in vec2 uv;
layout(location = 1) in vec4 vertexColor;
layout(location = 2) in float sphericalDistance;
layout(location = 3) in float cylindricalDistance;
layout(location = 0) out vec4 fragColor;

void main()
{
	vec4 tex = texture(RainTexture, uv);
	// Original particle shader: texture times world lightmap, not scene tint.
	vec4 color = tex * vertexColor;
	color.a *= RainAlpha;
	if (color.a < 0.1) discard;
	// 1.20.1 particle fog uses smoothstep (modern vanilla changed its curve).
	// Preserve that curve while respecting modern spherical environmental and
	// cylindrical render-distance ranges, including underwater/blindness fog.
	float environmentFog = sphericalDistance <= FogEnvironmentalStart ? 0.0
	    : sphericalDistance >= FogEnvironmentalEnd ? 1.0
	    : smoothstep(FogEnvironmentalStart, FogEnvironmentalEnd, sphericalDistance);
	float distanceFog = cylindricalDistance <= FogRenderDistanceStart ? 0.0
	    : cylindricalDistance >= FogRenderDistanceEnd ? 1.0
	    : smoothstep(FogRenderDistanceStart, FogRenderDistanceEnd, cylindricalDistance);
	fragColor = vec4(mix(color.rgb, FogColor.rgb, max(environmentFog, distanceFog) * FogColor.a), color.a);
}

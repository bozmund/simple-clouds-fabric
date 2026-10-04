#version 330
#extension GL_ARB_separate_shader_objects : require

// Distant Horizons LOD depth (reversed-Z, DH's own projection, cleared to 0) re-projected
// into Minecraft's depth so the late cloud passes are occluded by DH terrain.
#ifndef DH_MERGE_DEBUG
#include <minecraft:projection.glsl>
#endif

uniform sampler2D DhDepthSampler;
layout(std140) uniform DhDepthMerge {
	mat4 InverseDhProjMat;
	mat4 MainProjMat;
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main()
{
	float dhDepth = texture(DhDepthSampler, texCoord).r;
#ifdef DH_MERGE_DEBUG
	// Dev view: r = DH distance / 8000, g = DH depth * 200, b = re-projected depth * 2000.
	if (dhDepth <= 1.0e-7) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
	vec4 dv = InverseDhProjMat * vec4(texCoord * 2.0 - 1.0, dhDepth, 1.0);
	dv /= dv.w;
	vec4 dc = MainProjMat * vec4(dv.xyz, 1.0);
	fragColor = vec4(clamp(length(dv.xyz) / 8000.0, 0.0, 1.0), clamp(dhDepth * 200.0, 0.0, 1.0),
			clamp(dc.z / dc.w * 2000.0, 0.0, 1.0), 1.0);
#else
	if (dhDepth <= 1.0e-7)
		discard; // no LOD drawn here
	vec4 view = InverseDhProjMat * vec4(texCoord * 2.0 - 1.0, dhDepth, 1.0);
	view /= view.w;
	vec4 clip = ProjMat * vec4(view.xyz, 1.0);
	float depth = clip.z / clip.w;
	if (!(depth > 0.0) || depth > 1.0)
		discard;
	gl_FragDepth = depth;
	fragColor = vec4(0.0);
#endif
}

#version 330
#extension GL_ARB_separate_shader_objects : require

// Shadow-map pass vertex shader (26.2): renders the same per-instance cloud faces
// as the main pass, but from a top-down orthographic camera, into the shadow depth
// target. Only depth matters; the fragment shader writes nothing.
layout(location = 0) in vec3 Position;
layout(location = 1) in float Side;
layout(location = 2) in vec3 SidePos;
layout(location = 3) in float Radius;
layout(location = 4) in float Brightness;

layout(std140) uniform ShadowMatrices {
	mat4 ShadowModelViewMat;
	mat4 ShadowProjMat;
};

layout(location = 0) out float dummy;

#include <simpleclouds:cloud_faces.glsl>

void main()
{
	vec3 transformedPos = applySideTransform(Position, int(Side)) * Radius + SidePos;
	vec4 finalPos = vec4(transformedPos, 1.0);
	gl_Position = ShadowProjMat * ShadowModelViewMat * finalPos;
	dummy = 1.0;
}

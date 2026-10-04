#version 330
#extension GL_ARB_separate_shader_objects : require

// Original clouds_shadow_map vertex math after the GPU instance ABI adapter.
layout(location = 0) in vec3 Position;
layout(location = 1) in float Side;
layout(location = 2) in vec3 SidePos;
layout(location = 3) in float Radius;
layout(location = 4) in float Brightness;
layout(std140) uniform ShadowMatrices {
	mat4 ShadowModelViewMat;
	mat4 ShadowProjMat;
};
layout(std140) uniform StormShadow {
	vec4 HeightScale; // cloud base, inverse cloud scale, unused, unused
};
layout(location = 0) out vec4 vertexColor;
layout(location = 1) out float height;
#include <simpleclouds:cloud_faces.glsl>

void main()
{
	vec3 pos = applySideTransform(Position, int(Side)) * Radius + SidePos;
	gl_Position = ShadowProjMat * ShadowModelViewMat * vec4(pos, 1.0);
	height = (pos.y - HeightScale.x) * HeightScale.y;
	vertexColor = vec4(vec3(Brightness), 1.0);
}

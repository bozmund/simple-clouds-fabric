#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

// Lightning bolt section corner: world position + vertex color.
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;

layout(location = 0) out vec4 vColor;
layout(location = 1) out vec3 vViewPos;

void main()
{
	vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
	gl_Position = ProjMat * viewPos;
	vColor = Color;
	vViewPos = viewPos.xyz;
}

#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

// Lightning bolt section corner: world position + vertex color.
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;

layout(location = 0) out vec4 vColor;

void main()
{
	gl_Position = vec4(Position, 1.0) * transformations[0];
	vColor = Color;
}

#version 330
#extension GL_ARB_separate_shader_objects : require

// Additive (BlendFunction.LIGHTNING) pass: out = src*1 + dst*0, so the color
// is added to the scene. No fog: bolts read as bright flashes.
layout(location = 0) in vec4 vColor;

layout(location = 0) out vec4 fragColor;

void main()
{
	fragColor = vColor;
}

#version 330
#extension GL_ARB_separate_shader_objects : require

// Shadow-map pass fragment shader: only the depth buffer of this pass is consumed
// (cloud height above each XZ texel). Color is never written (write mask NONE).
layout(location = 0) in float dummy;

layout(location = 0) out vec4 fragColor;

void main()
{
	fragColor = vec4(0.0);
}

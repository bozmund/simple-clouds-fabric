#version 330
#extension GL_ARB_separate_shader_objects : require

// 26.2 sky flash vertex shader: a single screen-covering triangle in clip space
// (same shape as the storm fog pass).
layout(location = 0) in vec2 Position;

layout(location = 0) out vec2 texCoord;

void main()
{
	gl_Position = vec4(Position, 0.0, 1.0);
	texCoord = Position * 0.5 + 0.5;
}

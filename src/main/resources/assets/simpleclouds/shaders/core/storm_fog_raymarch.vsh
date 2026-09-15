#version 330

// Full-port storm fog vertex shader: a single screen-covering triangle in clip
// space (same shape as the slice storm fog). The fragment shader raymarches.
in vec2 Position;

out vec2 texCoord;

void main()
{
	gl_Position = vec4(Position, 0.0, 1.0);
	texCoord = Position * 0.5 + 0.5;
}

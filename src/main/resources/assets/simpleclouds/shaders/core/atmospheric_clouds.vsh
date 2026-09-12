#version 330

// Fullscreen triangle for the atmospheric (high cirrus-type) cloud layer.
in vec2 Position;
out vec2 texCoord;

void main()
{
	gl_Position = vec4(Position, 0.0, 1.0);
	texCoord = Position * 0.5 + 0.5;
}

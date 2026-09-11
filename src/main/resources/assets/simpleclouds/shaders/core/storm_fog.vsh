#version 330

// 26.2 storm fog vertex shader: a single screen-covering triangle in clip space.
// (The 1.20.1 storm fog was a 200-step raymarch over the cloud shadow map; that
// requires the shadow-map subsystem. This slice version is a fullscreen blend pass
// driven by CPU-measured storm coverage -- see storm_fog.fsh and PORTING.md.)
in vec2 Position;

out vec2 texCoord;

void main()
{
	gl_Position = vec4(Position, 0.0, 1.0);
	texCoord = Position * 0.5 + 0.5;
}

#version 330

// Shadow-map pass fragment shader: only the depth buffer of this pass is consumed
// (cloud height above each XZ texel). Color is never written (write mask NONE).
in float dummy;

out vec4 fragColor;

void main()
{
	fragColor = vec4(0.0);
}

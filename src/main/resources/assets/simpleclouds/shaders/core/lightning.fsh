#version 330

// Additive (BlendFunction.LIGHTNING) pass: out = src*1 + dst*0, so the color
// is added to the scene. No fog: bolts read as bright flashes.
in vec4 vColor;

out vec4 fragColor;

void main()
{
	fragColor = vColor;
}

#version 330

#moj_import <minecraft:dynamictransforms.glsl>

// Lightning bolt section corner: world position + vertex color.
in vec3 Position;
in vec4 Color;

out vec4 vColor;

void main()
{
	gl_Position = vec4(Position, 1.0) * transformations[0];
	vColor = Color;
}

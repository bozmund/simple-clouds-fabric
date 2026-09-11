#version 330

// Shadow-map pass vertex shader (26.2): renders the same per-instance cloud faces
// as the main pass, but from a top-down orthographic camera, into the shadow depth
// target. Only depth matters; the fragment shader writes nothing.
in vec3 Position;
in float Side;
in vec3 SidePos;
in float Radius;
in float Brightness;

layout(std140) uniform ShadowMatrices {
	mat4 ShadowModelViewMat;
	mat4 ShadowProjMat;
};

out float dummy;

vec3 applySideTransform(vec3 p, int side)
{
	if (side == 0) return p;
	if (side == 1) return vec3(-p.x, p.y, p.z);
	if (side == 2) return vec3(p.y, -p.x, p.z);
	if (side == 3) return vec3(p.y, p.x, p.z);
	if (side == 4) return vec3(p.z, p.y, -p.x);
	return vec3(-p.z, p.y, p.x);
}

void main()
{
	vec3 transformedPos = applySideTransform(Position, int(Side)) * Radius + SidePos;
	vec4 finalPos = vec4(transformedPos, 1.0);
	gl_Position = ShadowProjMat * ShadowModelViewMat * finalPos;
	dummy = 1.0;
}

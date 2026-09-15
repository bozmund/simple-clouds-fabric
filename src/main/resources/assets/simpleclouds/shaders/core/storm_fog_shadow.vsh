#version 330

// Storm-fog shadow-map vertex shader (26.2 port of the 1.20.1
// clouds_shadow_map.vsh): renders the same per-instance cloud faces as the main
// pass, but from a rotated top-down ortho camera (stormFogAngle + wind yaw) into
// the storm-fog shadow target. Unlike the terrain-shadow pass, this one also
// outputs the cloud BRIGHTNESS as colour (the raymarched storm fog samples it as
// the fog colour), and the world-space height (for the height cutoff).
in vec3 Position;
in float Side;
in vec3 SidePos;
in float Radius;
in float Brightness;

// Shared UBO (must match the fragment declaration). The vertex stage only needs
// the matrices; the fragment stage needs ColorModulator + HeightCutoff.
layout(std140) uniform StormFogShadowMatrices {
	mat4 ModelViewMat;
	mat4 ProjMat;
	vec4 ColorModulator;
	float HeightCutoff;
};

out vec4 vertexColor;
out float height;

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
	height = finalPos.y;
	gl_Position = ProjMat * ModelViewMat * finalPos;
	// 1.20.1 clouds_shadow_map.vsh: vertexColor = vec4(vec3(info.brightness), 1.0).
	vertexColor = vec4(Brightness, Brightness, Brightness, 1.0);
}

#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// Per-vertex: base quad corner.
in vec3 Position;
// Per-instance (step rate 1): one cloud face.
in float Side;
in vec3 SidePos;
in float Radius;
in float Brightness;

layout(std140) uniform CloudLighting {
	vec3 Light0_Direction;
	vec3 Light1_Direction;
	float LightPower;
	float AmbientLight;
};

layout(std140) uniform CloudShading {
	vec3 DarknessColorModifier;
	float UseNormals;
};

out vec4 vertexColor;
out float fogDistance;

// 26.2 (GLSL ES 3.0 / Vulkan): no C-style array initialization of constructors,
// so face normals and face transforms are provided by functions.
vec3 sideNormal(int side)
{
	if (side == 0) return vec3(-1.0, 0.0, 0.0);
	if (side == 1) return vec3(1.0, 0.0, 0.0);
	if (side == 2) return vec3(0.0, -1.0, 0.0);
	if (side == 3) return vec3(0.0, 1.0, 0.0);
	if (side == 4) return vec3(0.0, 0.0, -1.0);
	return vec3(0.0, 0.0, 1.0);
}

vec3 applySideTransform(vec3 p, int side)
{
	// Exact port of the former per-face 4x4 matrices (column-major).
	if (side == 0) return p;
	if (side == 1) return vec3(-p.x, p.y, p.z);
	if (side == 2) return vec3(p.y, -p.x, p.z);
	if (side == 3) return vec3(p.y, p.x, p.z);
	if (side == 4) return vec3(p.z, p.y, -p.x);
	return vec3(-p.z, p.y, p.x);
}

vec4 mixLight(vec3 lightDir0, vec3 lightDir1, vec3 normal, vec4 color)
{
	lightDir0 = normalize(lightDir0);
	lightDir1 = normalize(lightDir1);
	float light0 = max(0.0, dot(lightDir0, normal));
	float light1 = max(0.0, dot(lightDir1, normal));
	float lightAccum = min(1.0, (light0 + light1) * LightPower + AmbientLight);
	return vec4(vec3(color.r * lightAccum, color.g * lightAccum, color.b), color.a);
}

void main()
{
	int side = int(Side);

	vec3 transformedPos = applySideTransform(Position, side) * Radius + SidePos;
	vec4 finalPos = vec4(transformedPos, 1.0);
	gl_Position = ProjMat * ModelViewMat * finalPos;
	fogDistance = length((ModelViewMat * finalPos).xz);

	vec4 finalCol = vec4(mix(DarknessColorModifier, vec3(1.0), Brightness), 1.0);
	if (UseNormals > 0.5)
	{
		vec3 normal = sideNormal(side);
		vertexColor = mixLight(Light0_Direction, Light1_Direction, normal, finalCol);
	}
	else
	{
		vertexColor = finalCol;
	}
}

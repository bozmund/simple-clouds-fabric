#version 330
#extension GL_ARB_separate_shader_objects : require

// Additive (BlendFunction.LIGHTNING) pass: out = src*1 + dst*0, so the color
// is added to the scene. No fog: bolts read as bright flashes.
layout(location = 0) in vec4 vColor;
layout(location = 1) in vec3 vViewPos;

layout(location = 0) out vec4 fragColor;

// Distant Horizons LOD depth (reversed-Z, cleared to 0). The hardware depth test
// only sees vanilla terrain, so bolts behind DH terrain are hidden here instead,
// as the 1.20.1 original did by drawing them against DH's depth.
uniform sampler2D DhDepthSampler;
layout(std140) uniform LightningOcclusion {
	mat4 InverseDhProjMat;
	vec4 OcclusionParams; // enabled, 1/width, 1/height, unused
};

void main()
{
	if (OcclusionParams.x > 0.5)
	{
		vec2 uv = gl_FragCoord.xy * OcclusionParams.yz;
		float dhDepth = texture(DhDepthSampler, uv).r;
		if (dhDepth > 1.0e-7)
		{
			vec4 lod = InverseDhProjMat * vec4(uv * 2.0 - 1.0, dhDepth, 1.0);
			if (length(vViewPos) > length(lod.xyz / lod.w))
				discard;
		}
	}
	fragColor = vColor;
}

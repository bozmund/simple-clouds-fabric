#version 330

// Storm-fog shadow-map fragment shader (26.2 port of the 1.20.1
// clouds_shadow_map.fsh): writes the cloud brightness (tinted by ColorModulator)
// to the colour attachment; the depth attachment is written by the pipeline.
// Discards fragments above the height cutoff or with low alpha.
layout(std140) uniform StormFogShadowMatrices {
	mat4 ModelViewMat;
	mat4 ProjMat;
	vec4 ColorModulator;
	float HeightCutoff;
};

in vec4 vertexColor;
in float height;

out vec4 fragColor;

void main()
{
	vec4 color = ColorModulator * vertexColor;
	if (height > HeightCutoff || color.a < 0.1)
		discard;
	fragColor = color;
}

#version 430
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D DiffuseSampler;
uniform sampler2D CloudsTexture;
uniform sampler2D CloudsDepthTexture;

layout(location = 0) in vec2 texCoord;
layout(location = 1) in vec2 oneTexel;
layout(location = 0) out vec4 fragColor;

void main() 
{
	vec4 cloudCol = texture(CloudsTexture, texCoord);
	vec3 bg = texture(DiffuseSampler, texCoord).rgb;
	vec3 finalCol = bg;
	finalCol = vec3(cloudCol.rgb * cloudCol.a + finalCol * (1.0 - cloudCol.a));
	fragColor = vec4(finalCol, 1.0);
}

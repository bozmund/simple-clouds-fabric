#version 150
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D DiffuseSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 1) in vec2 sampleStep;

uniform float Radius;
uniform float RadiusMultiplier;

layout(location = 0) out vec4 fragColor;

void main() 
{
    vec4 blurred = vec4(0.0);
    float actualRadius = round(Radius * RadiusMultiplier);
    for (float a = -actualRadius + 0.5; a <= actualRadius; a += 2.0)
        blurred += texture(DiffuseSampler, texCoord + sampleStep * a);
    blurred += texture(DiffuseSampler, texCoord + sampleStep * actualRadius) / 2.0;
    fragColor = blurred / (actualRadius + 0.5);
}

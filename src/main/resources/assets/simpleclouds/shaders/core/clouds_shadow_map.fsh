#version 150
#extension GL_ARB_separate_shader_objects : require

uniform vec4 ColorModulator;
uniform float HeightCutoff;

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in float height;

layout(location = 0) out vec4 fragColor;

void main() 
{
    vec4 color = ColorModulator * vertexColor;
    if (height > HeightCutoff || color.a < 0.1)
        discard;
    fragColor = color;
}

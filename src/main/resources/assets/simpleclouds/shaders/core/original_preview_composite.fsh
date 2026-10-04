#version 330
uniform sampler2D CloudsTexture;
uniform sampler2D AccumTexture;
uniform sampler2D RevealageTexture;
layout(location = 0) out vec4 fragColor;

// Factor the original clouds_composite equation over the existing destination.
// Sampling that destination while drawing into it is deliberately avoided.
void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 opaque = texelFetch(CloudsTexture, pixel, 0);
    float reveal = texelFetch(RevealageTexture, pixel, 0).r;
    vec4 accum = texelFetch(AccumTexture, pixel, 0);
    if (isinf(max(max(abs(accum.r), abs(accum.g)), max(abs(accum.b), abs(accum.a)))))
        accum.rgb = vec3(accum.a);
    vec3 average = accum.rgb / max(accum.a, 0.00001);
    float coverage = opaque.a * reveal + 1.0 - reveal;
    vec3 premultiplied = opaque.rgb * opaque.a * reveal + average * (1.0 - reveal);
    fragColor = vec4(premultiplied / max(coverage, 0.00001), coverage);
}

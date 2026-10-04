#version 330
#extension GL_ARB_separate_shader_objects : require
layout(std140) uniform OriginalWorldFog {
    mat4 InverseProjection;
    mat4 InverseViewRotation;
    vec4 FogColor;
    float FogStart;
    float FogEnd;
    int FogShape;
    int HasStorm;
};
uniform sampler2D DiffuseSampler;
uniform sampler2D DiffuseDepthSampler;
uniform sampler2D CloudDepthSampler;
uniform sampler2D PreCloudDepthSampler;
uniform sampler2D StormFogSampler;
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main() {
    vec3 scene = texture(DiffuseSampler,texCoord).rgb;
    float depth = texture(DiffuseDepthSampler,texCoord).r;
    // Modern reversed-Z: cloud pixels (where the cloud pass moved the depth closer and nothing
    // nearer covered them since) and the clear sky stay untouched.
    float cloudDepth = texture(CloudDepthSampler,texCoord).r;
    bool cloudPixel = cloudDepth > texture(PreCloudDepthSampler,texCoord).r && depth <= cloudDepth;
    if(depth <= 0.0 || cloudPixel) {
        fragColor=vec4(scene,1.0); return;
    }
    vec4 view = InverseProjection * vec4(texCoord*2.0-1.0,depth,1.0);
    vec3 pos = (InverseViewRotation * (view/view.w)).xyz;
    float dist = FogShape==0 ? length(pos) : max(length(pos.xz),abs(pos.y));
    // Endpoint guards also preserve deliberately disabled native fog ranges.
    float fog = dist<=FogStart ? 0.0 : dist>=FogEnd ? 1.0 : smoothstep(FogStart,FogEnd,dist);
    vec4 storm = HasStorm!=0 ? texture(StormFogSampler,texCoord) : vec4(0.0);
    vec3 color = mix(FogColor.rgb,storm.rgb,storm.a);
    fragColor=vec4(mix(scene,color,fog),1.0);
}

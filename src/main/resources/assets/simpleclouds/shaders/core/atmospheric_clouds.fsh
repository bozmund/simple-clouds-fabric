#version 330

// Atmospheric clouds (26.2 port of the 1.20.1 post/program/atmospheric_clouds.fsh):
// a purely visual high cloud layer. Each fragment casts a ray up to a plane
// HEIGHT above the camera and samples psrdnoise there, mixed in with the biome
// formation's density/color. No depth readback needed: the ray direction is
// rebuilt from NDC + FOV + the camera rotation (mat3 of the view matrix), which
// is exactly what the original's InverseWorldProj/InverseModelView gave.
in vec2 texCoord;
out vec4 fragColor;

uniform sampler2D DiffuseSampler;

// Declared here (the device does NOT inject sampler/uniform declarations from
// the bind group layout -- 26.2 GlProgram compiles the source as written).
layout(std140) uniform AtmosphericPass {
	mat4 ViewMat;        // world -> camera (rotation part used)
	mat2 Transform;      // formation scale + wind yaw
	float PixelScale;
	float SpanX;
	float SpanZ;
	float MaxDist;
	float FadeStart;
	float ShiftMovement;
	float CloudDensity;
	float TanHalfFov;    // tan(fov/2); NDC -> camera-space ray
	float Aspect;
	vec4 CloudColor;
};

// psrdnoise (c) Stefan Gustavson and Ian McEwan, ver. 2021-12-02, MIT license.
vec4 permute(vec4 i) {
	vec4 im = mod(i, 289.0);
	return mod(((im * 34.0) + 10.0) * im, 289.0);
}

float psrdnoise(vec3 x, vec3 period, float alpha, out vec3 gradient)
{
	const mat3 M = mat3(0.0, 1.0, 1.0, 1.0, 0.0, 1.0, 1.0, 1.0, 0.0);
	const mat3 Mi = mat3(-0.5, 0.5, 0.5, 0.5, -0.5, 0.5, 0.5, 0.5, -0.5);
	vec3 uvw = M * x;
	vec3 i0 = floor(uvw), f0 = fract(uvw);
	vec3 g_ = step(f0.xyx, f0.yzz), l_ = 1.0 - g_;
	vec3 g = vec3(l_.z, g_.xy), l = vec3(l_.xy, g_.z);
	vec3 o1 = min(g, l), o2 = max(g, l);
	vec3 i1 = i0 + o1, i2 = i0 + o2, i3 = i0 + vec3(1.0);
	vec3 v0 = Mi * i0, v1 = Mi * i1, v2 = Mi * i2, v3 = Mi * i3;
	vec3 x0 = x - v0, x1 = x - v1, x2 = x - v2, x3 = x - v3;
	if (any(greaterThan(period, vec3(0.0))))
	{
		vec4 vx = vec4(v0.x, v1.x, v2.x, v3.x);
		vec4 vy = vec4(v0.y, v1.y, v2.y, v3.y);
		vec4 vz = vec4(v0.z, v1.z, v2.z, v3.z);
		if (period.x > 0.0) vx = mod(vx, period.x);
		if (period.y > 0.0) vy = mod(vy, period.y);
		if (period.z > 0.0) vz = mod(vz, period.z);
		i0 = floor(M * vec3(vx.x, vy.x, vz.x) + 0.5);
		i1 = floor(M * vec3(vx.y, vy.y, vz.y) + 0.5);
		i2 = floor(M * vec3(vx.z, vy.z, vz.z) + 0.5);
		i3 = floor(M * vec3(vx.w, vy.w, vz.w) + 0.5);
	}
	vec4 hash = permute(permute(permute(vec4(i0.z, i1.z, i2.z, i3.z)))
		+ vec4(i0.y, i1.y, i2.y, i3.y))
		+ vec4(i0.x, i1.x, i2.x, i3.x);
	vec4 theta = hash * 3.883222077;
	vec4 sz = hash * -0.006920415 + 0.996539792;
	vec4 psi = hash * 0.108705628;
	vec4 Ct = cos(theta), St = sin(theta);
	vec4 sz_prime = sqrt(1.0 - sz * sz);
	vec4 gx, gy, gz;
	if (alpha != 0.0)
	{
		vec4 px = Ct * sz_prime, py = St * sz_prime, pz = sz;
		vec4 Sp = sin(psi), Cp = cos(psi), Ctp = St * Sp - Ct * Cp;
		vec4 qx = mix(Ctp * St, Sp, sz), qy = mix(-Ctp * Ct, Cp, sz);
		vec4 qz = -(py * Cp + px * Sp);
		vec4 Sa = vec4(sin(alpha)), Ca = vec4(cos(alpha));
		gx = Ca * px + Sa * qx;
		gy = Ca * py + Sa * qy;
		gz = Ca * pz + Sa * qz;
	}
	else
	{
		gx = Ct * sz_prime;
		gy = St * sz_prime;
		gz = sz;
	}
	vec3 g0 = vec3(gx.x, gy.x, gz.x), g1 = vec3(gx.y, gy.y, gz.y);
	vec3 g2 = vec3(gx.z, gy.z, gz.z), g3 = vec3(gx.w, gy.w, gz.w);
	vec4 w = 0.5 - vec4(dot(x0, x0), dot(x1, x1), dot(x2, x2), dot(x3, x3));
	w = max(w, 0.0);
	vec4 w2 = w * w, w3 = w2 * w;
	vec4 gdotx = vec4(dot(g0, x0), dot(g1, x1), dot(g2, x2), dot(g3, x3));
	float n = dot(w3, gdotx);
	vec4 dw = -6.0 * w2 * gdotx;
	vec3 dn0 = w3.x * g0 + dw.x * x0;
	vec3 dn1 = w3.y * g1 + dw.y * x1;
	vec3 dn2 = w3.z * g2 + dw.z * x2;
	vec3 dn3 = w3.w * g3 + dw.w * x3;
	gradient = 39.5 * (dn0 + dn1 + dn2 + dn3);
	return 39.5 * n;
}

void main()
{
	vec4 col = texture(DiffuseSampler, texCoord);

	if (CloudDensity <= 0.01)
	{
		fragColor = col;
		return;
	}

	// NDC fragment -> camera-space ray (camera looks down -Z, so the forward
	// component is negative), then to world space via the transpose of the view
	// rotation.
	vec2 uv = texCoord * 2.0 - 1.0;
	vec3 dirCam = normalize(vec3(uv.x * Aspect, uv.y, -1.0 / TanHalfFov));
	vec3 rayDir = transpose(mat3(ViewMat)) * dirCam;

	// A horizontal/downward ray never intersects the layer above the camera.
	if (rayDir.y <= 0.00001)
	{
		fragColor = col;
		return;
	}
	float rayLen = 5000.0 / rayDir.y; // plane 5000 blocks above the camera
	if (rayLen <= 0.0)
	{
		fragColor = col;
		return;
	}
	vec3 point = rayLen * rayDir;

	float len = length(point.xz);
	if (len > MaxDist)
	{
		fragColor = col;
		return;
	}
	float fade = clamp((MaxDist - len) / (MaxDist - FadeStart), 0.0, 1.0);

	vec2 nsp = floor(point.xz / PixelScale) * PixelScale / vec2(SpanX, SpanZ);
	nsp = Transform * nsp;

	vec3 gradient = vec3(0.0);
	float factor = psrdnoise(vec3(nsp, ShiftMovement), vec3(32.0), 0.0, gradient) * 0.5 + 0.5;
	factor = clamp(factor - 1.0 + CloudDensity, 0.0, 1.0);
	col.rgb = mix(col.rgb, CloudColor.rgb, factor * fade * CloudColor.a);

	fragColor = col;
}

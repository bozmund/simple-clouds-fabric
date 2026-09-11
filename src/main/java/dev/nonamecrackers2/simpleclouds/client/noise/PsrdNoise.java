package dev.nonamecrackers2.simpleclouds.client.noise;

/**
 * CPU port of psrdnoise (periodic simplex-like gradient noise).
 *
 * Original: Stefan Gustavson and Ian McEwan, MIT license
 * (https://github.com/stegu/psrdnoise/). This is a faithful scalar port of the
 * mod's GLSL implementation (assets/simpleclouds/shaders/include/psrdnoise.glsl),
 * which the new 26.2 backend cannot run (no compute dispatch).
 *
 * The gradient is written into the supplied 3-element float array.
 */
public final class PsrdNoise
{
	private PsrdNoise()
	{
	}

	/** permute from psrdnoise.glsl, scalarized. */
	private static float permute(float i)
	{
		float im = i % 289.0F;
		return ((im * 34.0F) + 10.0F) * im % 289.0F;
	}

	/**
	 * Computes psrdnoise at (x, y, z) with the given period and alpha, writing
	 * the gradient into {@code gradientOut[0..2]}.
	 *
	 * @return the noise value.
	 */
	public static float noise(float x, float y, float z, float periodX, float periodY, float periodZ, float alpha, float[] gradientOut)
	{
		// uvw = M * x, where M = [0 1 1; 1 0 1; 1 1 0]
		float uvwx = y + z;
		float uvwy = x + z;
		float uvwz = x + y;

		float i0x = (float) Math.floor(uvwx);
		float i0y = (float) Math.floor(uvwy);
		float i0z = (float) Math.floor(uvwz);
		float f0x = uvwx - i0x;
		float f0y = uvwy - i0y;
		float f0z = uvwz - i0z;

		// g_ = step(f0.xyx, f0.yzz), l_ = 1 - g_
		// step(a, b) = b >= a ? 1 : 0
		// g_.x = step(f0.y, f0.y) = 1 ; g_.y = step(f0.x, f0.z) ; g_.z = step(f0.y, f0.x)
		float g_x = 1.0F; // step(f0.xyx.x = f0.y, f0.yzz.x = f0.y) -> always 1
		float g_y = f0x >= f0z ? 1.0F : 0.0F; // step(f0.x, f0.z)
		float g_z = f0y >= f0x ? 1.0F : 0.0F; // step(f0.y, f0.x)
		float l_x = 1.0F - g_x;
		float l_y = 1.0F - g_y;
		float l_z = 1.0F - g_z;

		// g = vec3(l_.z, g_.xy), l = vec3(l_.xy, g_.z)
		float g0x = l_z, g0y = g_x, g0z = g_y;
		float l0x = l_x, l0y = l_y, l0z = g_z;

		// o1 = min(g, l), o2 = max(g, l)
		float o1x = Math.min(g0x, l0x), o1y = Math.min(g0y, l0y), o1z = Math.min(g0z, l0z);
		float o2x = Math.max(g0x, l0x), o2y = Math.max(g0y, l0y), o2z = Math.max(g0z, l0z);

		float i1x = i0x + o1x, i1y = i0y + o1y, i1z = i0z + o1z;
		float i2x = i0x + o2x, i2y = i0y + o2y, i2z = i0z + o2z;
		float i3x = i0x + 1.0F, i3y = i0y + 1.0F, i3z = i0z + 1.0F;

		// vN = Mi * iN, Mi = [-0.5 0.5 0.5; 0.5 -0.5 0.5; 0.5 0.5 -0.5]
		float v0x = -0.5F * i0x + 0.5F * i0y + 0.5F * i0z;
		float v0y = 0.5F * i0x - 0.5F * i0y + 0.5F * i0z;
		float v0z = 0.5F * i0x + 0.5F * i0y - 0.5F * i0z;
		float v1x = -0.5F * i1x + 0.5F * i1y + 0.5F * i1z;
		float v1y = 0.5F * i1x - 0.5F * i1y + 0.5F * i1z;
		float v1z = 0.5F * i1x + 0.5F * i1y - 0.5F * i1z;
		float v2x = -0.5F * i2x + 0.5F * i2y + 0.5F * i2z;
		float v2y = 0.5F * i2x - 0.5F * i2y + 0.5F * i2z;
		float v2z = 0.5F * i2x + 0.5F * i2y - 0.5F * i2z;
		float v3x = -0.5F * i3x + 0.5F * i3y + 0.5F * i3z;
		float v3y = 0.5F * i3x - 0.5F * i3y + 0.5F * i3z;
		float v3z = 0.5F * i3x + 0.5F * i3y - 0.5F * i3z;

		// xN = x - vN
		float x0x = x - v0x, x0y = y - v0y, x0z = z - v0z;
		float x1x = x - v1x, x1y = y - v1y, x1z = z - v1z;
		float x2x = x - v2x, x2y = y - v2y, x2z = z - v2z;
		float x3x = x - v3x, x3y = y - v3y, x3z = z - v3z;

		// Periodic wrapping
		if (periodX > 0.0F || periodY > 0.0F || periodZ > 0.0F)
		{
			if (periodX > 0.0F)
			{
				v0x = v0x % periodX; v1x = v1x % periodX; v2x = v2x % periodX; v3x = v3x % periodX;
			}
			if (periodY > 0.0F)
			{
				v0y = v0y % periodY; v1y = v1y % periodY; v2y = v2y % periodY; v3y = v3y % periodY;
			}
			if (periodZ > 0.0F)
			{
				v0z = v0z % periodZ; v1z = v1z % periodZ; v2z = v2z % periodZ; v3z = v3z % periodZ;
			}
			// iN = floor(M * vN + 0.5)
			i0x = (float) Math.floor(v0y + v0z + 0.5F); i0y = (float) Math.floor(v0x + v0z + 0.5F); i0z = (float) Math.floor(v0x + v0y + 0.5F);
			i1x = (float) Math.floor(v1y + v1z + 0.5F); i1y = (float) Math.floor(v1x + v1z + 0.5F); i1z = (float) Math.floor(v1x + v1y + 0.5F);
			i2x = (float) Math.floor(v2y + v2z + 0.5F); i2y = (float) Math.floor(v2x + v2z + 0.5F); i2z = (float) Math.floor(v2x + v2y + 0.5F);
			i3x = (float) Math.floor(v3y + v3z + 0.5F); i3y = (float) Math.floor(v3x + v3z + 0.5F); i3z = (float) Math.floor(v3x + v3y + 0.5F);
		}

		float h0 = permute(permute(permute(i0z) + i0y) + i0x);
		float h1 = permute(permute(permute(i1z) + i1y) + i1x);
		float h2 = permute(permute(permute(i2z) + i2y) + i2x);
		float h3 = permute(permute(permute(i3z) + i3y) + i3x);

		float theta0 = h0 * 3.883222077F, theta1 = h1 * 3.883222077F, theta2 = h2 * 3.883222077F, theta3 = h3 * 3.883222077F;
		float sz0 = h0 * -0.006920415F + 0.996539792F, sz1 = h1 * -0.006920415F + 0.996539792F, sz2 = h2 * -0.006920415F + 0.996539792F, sz3 = h3 * -0.006920415F + 0.996539792F;
		float psi0 = h0 * 0.108705628F, psi1 = h1 * 0.108705628F, psi2 = h2 * 0.108705628F, psi3 = h3 * 0.108705628F;

		float Ct0 = (float) Math.cos(theta0), St0 = (float) Math.sin(theta0);
		float Ct1 = (float) Math.cos(theta1), St1 = (float) Math.sin(theta1);
		float Ct2 = (float) Math.cos(theta2), St2 = (float) Math.sin(theta2);
		float Ct3 = (float) Math.cos(theta3), St3 = (float) Math.sin(theta3);
		float szp0 = (float) Math.sqrt(1.0F - sz0 * sz0), szp1 = (float) Math.sqrt(1.0F - sz1 * sz1), szp2 = (float) Math.sqrt(1.0F - sz2 * sz2), szp3 = (float) Math.sqrt(1.0F - sz3 * sz3);

		float[] g0 = new float[3], g1 = new float[3], g2 = new float[3], g3 = new float[3];
		if (alpha != 0.0F)
		{
			gradientWithAlpha(Ct0, St0, szp0, sz0, psi0, alpha, g0);
			gradientWithAlpha(Ct1, St1, szp1, sz1, psi1, alpha, g1);
			gradientWithAlpha(Ct2, St2, szp2, sz2, psi2, alpha, g2);
			gradientWithAlpha(Ct3, St3, szp3, sz3, psi3, alpha, g3);
		}
		else
		{
			g0[0] = Ct0 * szp0; g0[1] = St0 * szp0; g0[2] = sz0;
			g1[0] = Ct1 * szp1; g1[1] = St1 * szp1; g1[2] = sz1;
			g2[0] = Ct2 * szp2; g2[1] = St2 * szp2; g2[2] = sz2;
			g3[0] = Ct3 * szp3; g3[1] = St3 * szp3; g3[2] = sz3;
		}
		float gx0 = g0[0], gy0 = g0[1], gz0 = g0[2];
		float gx1 = g1[0], gy1 = g1[1], gz1 = g1[2];
		float gx2 = g2[0], gy2 = g2[1], gz2 = g2[2];
		float gx3 = g3[0], gy3 = g3[1], gz3 = g3[2];

		float w0 = 0.5F - (x0x * x0x + x0y * x0y + x0z * x0z);
		float w1 = 0.5F - (x1x * x1x + x1y * x1y + x1z * x1z);
		float w2 = 0.5F - (x2x * x2x + x2y * x2y + x2z * x2z);
		float w3 = 0.5F - (x3x * x3x + x3y * x3y + x3z * x3z);
		w0 = Math.max(w0, 0.0F); w1 = Math.max(w1, 0.0F); w2 = Math.max(w2, 0.0F); w3 = Math.max(w3, 0.0F);
		float w20 = w0 * w0, w21 = w1 * w1, w22 = w2 * w2, w23 = w3 * w3;
		float w30 = w20 * w0, w31 = w21 * w1, w32 = w22 * w2, w33 = w23 * w3;

		float gdotx0 = gx0 * x0x + gy0 * x0y + gz0 * x0z;
		float gdotx1 = gx1 * x1x + gy1 * x1y + gz1 * x1z;
		float gdotx2 = gx2 * x2x + gy2 * x2y + gz2 * x2z;
		float gdotx3 = gx3 * x3x + gy3 * x3y + gz3 * x3z;

		float n = w30 * gdotx0 + w31 * gdotx1 + w32 * gdotx2 + w33 * gdotx3;

		float dw0 = -6.0F * w20 * gdotx0;
		float dw1 = -6.0F * w21 * gdotx1;
		float dw2 = -6.0F * w22 * gdotx2;
		float dw3 = -6.0F * w23 * gdotx3;

		gradientOut[0] = 39.5F * ((w30 * gx0 + dw0 * x0x) + (w31 * gx1 + dw1 * x1x) + (w32 * gx2 + dw2 * x2x) + (w33 * gx3 + dw3 * x3x));
		gradientOut[1] = 39.5F * ((w30 * gy0 + dw0 * x0y) + (w31 * gy1 + dw1 * x1y) + (w32 * gy2 + dw2 * x2y) + (w33 * gy3 + dw3 * x3y));
		gradientOut[2] = 39.5F * ((w30 * gz0 + dw0 * x0z) + (w31 * gz1 + dw1 * x1z) + (w32 * gz2 + dw2 * x2z) + (w33 * gz3 + dw3 * x3z));

		return 39.5F * n;
	}

	/**
	 * Computes the (gx, gy, gz) gradient for a single lattice point when alpha != 0,
	 * matching the GLSL:
	 * <pre>
	 * px = Ct*szp; py = St*szp; pz = sz;
	 * Sp = sin(psi); Cp = cos(psi); Ctp = St*Sp - Ct*Cp;
	 * qx = mix(Ctp*St, Sp, sz); qy = mix(-Ctp*Ct, Cp, sz);
	 * qz = -(py*Cp + px*Sp);
	 * Sa = sin(alpha); Ca = cos(alpha);
	 * gx = Ca*px + Sa*qx; gy = Ca*py + Sa*qy; gz = Ca*pz + Sa*qz;
	 * </pre>
	 */
	private static void gradientWithAlpha(float Ct, float St, float szp, float sz, float psi, float alpha, float[] out)
	{
		float px = Ct * szp;
		float py = St * szp;
		float pz = sz;
		float Sp = (float) Math.sin(psi);
		float Cp = (float) Math.cos(psi);
		float Ctp = St * Sp - Ct * Cp;
		float qx = sz * Sp + (1.0F - sz) * (Ctp * St);
		float qy = sz * Cp + (1.0F - sz) * (-Ctp * Ct);
		float qz = -(py * Cp + px * Sp);
		float Sa = (float) Math.sin(alpha);
		float Ca = (float) Math.cos(alpha);
		out[0] = Ca * px + Sa * qx;
		out[1] = Ca * py + Sa * qy;
		out[2] = Ca * pz + Sa * qz;
	}
}

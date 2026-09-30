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

	// permute() returns one of 289 integer hashes. A cloud job uses the same
	// alpha for many thousands of samples; reuse its rotated lattice gradients
	// instead of evaluating their trigonometry at all four corners of every cell.
	// Per-thread state avoids synchronization and sharing mutable worker scratch.
	private static final ThreadLocal<GradientCache> GRADIENTS =
			ThreadLocal.withInitial(GradientCache::new);

	/** Worker-owned sampler. Do not share between concurrent generators. The
	 * static API remains thread-safe; this instance avoids ThreadLocal lookup in
	 * the per-voxel path when the caller already owns its generator scratch. */
	public static final class Sampler
	{
		private final GradientCache gradients = new GradientCache();

		public float noise(float x, float y, float z, float periodX, float periodY,
				float periodZ, float alpha, float[] gradientOut)
		{
			return PsrdNoise.noise(x, y, z, periodX, periodY, periodZ, alpha,
					gradientOut, this.gradients.at(alpha));
		}
	}

	private static final class GradientCache
	{
		private int alphaBits;
		private boolean ready;
		private final float[][] values = new float[289][3];

		float[][] at(float alpha)
		{
			int bits = Float.floatToIntBits(alpha);
			if (!ready || bits != alphaBits)
			{
				for (int i = 0; i < values.length; i++)
					latticeGradient(i, alpha, values[i]);
				alphaBits = bits;
				ready = true;
			}
			return values;
		}
	}

	private static void latticeGradient(float h, float alpha, float[] out)
	{
		float theta = h * 3.883222077F;
		float sz = h * -0.006920415F + 0.996539792F;
		float psi = h * 0.108705628F;
		float ct = (float) Math.cos(theta), st = (float) Math.sin(theta);
		float szp = (float) Math.sqrt(1.0F - sz * sz);
		if (alpha != 0.0F)
			gradientWithAlpha(ct, st, szp, sz, psi, alpha, out);
		else
		{
			out[0] = ct * szp;
			out[1] = st * szp;
			out[2] = sz;
		}
	}

	private static float[] gradientFor(float h, float alpha, float[][] cached)
	{
		int index = (int) h;
		if (index >= 0 && index < cached.length && h == index)
			return cached[index];
		// Retain the scalar behavior even for unusual non-integer/invalid inputs.
		float[] out = new float[3];
		latticeGradient(h, alpha, out);
		return out;
	}

	/** GLSL mod uses floor, unlike Java remainder for negative coordinates. */
	private static float mod(float x, float divisor)
	{
		return x - divisor * (float) Math.floor(x / divisor);
	}

	/** permute from psrdnoise.glsl, scalarized. */
	private static float permute(float i)
	{
		float im = mod(i, 289.0F);
		return mod(((im * 34.0F) + 10.0F) * im, 289.0F);
	}

	/**
	 * Computes psrdnoise at (x, y, z) with the given period and alpha, writing
	 * the gradient into {@code gradientOut[0..2]}.
	 *
	 * @return the noise value.
	 */
	public static float noise(float x, float y, float z, float periodX, float periodY, float periodZ, float alpha, float[] gradientOut)
	{
		return noise(x, y, z, periodX, periodY, periodZ, alpha, gradientOut,
				GRADIENTS.get().at(alpha));
	}

	private static float noise(float x, float y, float z, float periodX, float periodY,
			float periodZ, float alpha, float[] gradientOut, float[][] cached)
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
		// Edges are (x, y, x); compared values are (y, z, z).
		float g_x = f0y >= f0x ? 1.0F : 0.0F;
		float g_y = f0z >= f0y ? 1.0F : 0.0F;
		float g_z = f0z >= f0x ? 1.0F : 0.0F;
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
				v0x = mod(v0x, periodX); v1x = mod(v1x, periodX); v2x = mod(v2x, periodX); v3x = mod(v3x, periodX);
			}
			if (periodY > 0.0F)
			{
				v0y = mod(v0y, periodY); v1y = mod(v1y, periodY); v2y = mod(v2y, periodY); v3y = mod(v3y, periodY);
			}
			if (periodZ > 0.0F)
			{
				v0z = mod(v0z, periodZ); v1z = mod(v1z, periodZ); v2z = mod(v2z, periodZ); v3z = mod(v3z, periodZ);
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

		float[] g0 = gradientFor(h0, alpha, cached), g1 = gradientFor(h1, alpha, cached);
		float[] g2 = gradientFor(h2, alpha, cached), g3 = gradientFor(h3, alpha, cached);
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

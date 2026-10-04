import dev.nonamecrackers2.simpleclouds.client.renderer.v2.OriginalStormShadowTransform;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Original PoseStack/ShadowMapBuffer formula, independently assembled. */
public class OriginalStormShadowTransformTest
{
	static void near(float a, float b, String label) {
		if (Math.abs(a-b)>0.0001F) throw new AssertionError(label+": "+a+" != "+b);
	}
	public static void main(String[] args) {
		float span=5632, base=128;
		for (double camX : new double[]{-257,-256,-1,0,255,256,257})
		for (double camZ : new double[]{-257,-1,0,255,256})
		for (float angle : new float[]{50,80,90})
		for (float yaw : new float[]{0,1.1F,-2.2F}) {
			float wx=(float)Math.sin(yaw), wz=(float)Math.cos(yaw);
			Matrix4f expected=new Matrix4f().translate(span/2,span/2,-5000)
					.rotateX((float)Math.toRadians(angle)).rotateY((float)Math.atan2(wx,wz))
					.translate(-(float)Math.floor(camX/256)*256,-base,-(float)Math.floor(camZ/256)*256);
			Matrix4f actual=OriginalStormShadowTransform.view(camX,camZ,base,span,angle,wx,wz);
			float[] a=actual.get(new float[16]), e=expected.get(new float[16]);
			for(int i=0;i<16;i++) near(a[i],e[i],"view coefficient "+i);
			Vector3f center=new Vector3f((float)Math.floor(camX/256)*256,base,(float)Math.floor(camZ/256)*256);
			Vector4f clip=new Vector4f(center,1).mul(actual).mul(OriginalStormShadowTransform.projection(span));
			near(clip.x,0,"center x"); near(clip.y,0,"center y"); near(clip.z,0.5F,"center depth"); near(clip.w,1,"affine w");
			// 26.3 runs with glClipControl(ZERO_TO_ONE): the stored depth is NDC z, and it must
			// equal the Forge original's window depth (GL [-1,1] ortho, then *0.5+0.5).
			Matrix4f forgeProjection=new Matrix4f().setOrtho(0,span,span,0,0,5000*2);
			for (float dy : new float[]{-600,0,256,1200,2400})
			for (float dx : new float[]{-1500,0,900}) {
				Vector4f p=new Vector4f(center.x+dx,base+dy,center.z-dx*0.5F,1).mul(actual);
				Vector4f ours=new Vector4f(p).mul(OriginalStormShadowTransform.projection(span));
				Vector4f forge=new Vector4f(p).mul(forgeProjection);
				near(ours.z/ours.w,(forge.z/forge.w)*0.5F+0.5F,"window depth parity dy="+dy+" dx="+dx);
				if (ours.z/ours.w<0 || ours.z/ours.w>1) throw new AssertionError("Volume point clipped at dy="+dy);
			}
			Vector4f lightSide=new Vector4f(center.x,base+4000,center.z,1).mul(actual).mul(OriginalStormShadowTransform.projection(span));
			if (!(lightSide.z/lightSide.w<0.5F)) throw new AssertionError("Higher point is not closer to the light");
		}
		Matrix4f sameChunkA=OriginalStormShadowTransform.view(0,0,base,span,80,0,1);
		Matrix4f sameChunkB=OriginalStormShadowTransform.view(255,255,base,span,80,0,1);
		if(!sameChunkA.equals(sameChunkB)) throw new AssertionError("Subchunk camera motion moved volume");
		for(float invalid:new float[]{0,-1,Float.NaN,Float.POSITIVE_INFINITY}) {
			try { OriginalStormShadowTransform.projection(invalid); throw new AssertionError("Accepted invalid span"); }
			catch(IllegalArgumentException expected) {}
		}
		System.out.println("PASS original storm shadow matrix parity, negative snapping, zero-to-one depth parity and invalid span");
	}
}

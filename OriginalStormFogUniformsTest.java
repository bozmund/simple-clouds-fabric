import dev.nonamecrackers2.simpleclouds.client.renderer.v2.OriginalStormFogUniforms;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class OriginalStormFogUniformsTest {
	static void require(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
	public static void main(String[] args) {
		ByteBuffer buffer=ByteBuffer.allocateDirect(OriginalStormFogUniforms.BYTES).order(ByteOrder.nativeOrder());
		Matrix4f projection=new Matrix4f().setPerspective((float)Math.toRadians(70),16f/9,3000,0.05f,true);
		Matrix4f view=new Matrix4f().rotateY(0.7f).translate(-123,-70,91);
		Matrix4f pBefore=new Matrix4f(projection), vBefore=new Matrix4f(view);
		float[] bolts=new float[64]; for(int i=0;i<bolts.length;i++) bolts[i]=i+1;
		OriginalStormFogUniforms.write(buffer,projection,view,new Matrix4f(),new Matrix4f(),123,70,-91,
				2800,new float[]{0.1f,0.2f,0.3f},bolts,16,0);
		require(buffer.getFloat(268)==16 && buffer.getFloat(556)==64,"16-bolt std140 layout");
		require(buffer.getFloat(272)==1400 && buffer.getFloat(276)==2800,"Original fog start/end");
		require(projection.equals(pBefore) && view.equals(vBefore),"Uniform write mutated caller matrices");
		Matrix4f inverseProjection=new Matrix4f().set(0,buffer), inverseView=new Matrix4f().set(64,buffer);
		for(float distance:new float[]{0.06f,1,30,300,2500}) {
			Vector4f cameraPoint=new Vector4f(0.01f*distance,0.02f*distance,-distance,1);
			Vector4f world=new Vector4f(cameraPoint).mul(inverseView);
			Vector4f clip=new Vector4f(cameraPoint).mul(projection); clip.div(clip.w);
			require(clip.z>0 && clip.z<1,"Reversed-Z fixture outside depth range");
			Vector4f restored=new Vector4f(clip.x,clip.y,clip.z,1).mul(inverseProjection); restored.div(restored.w).mul(inverseView);
			float error=new org.joml.Vector3f(restored.x-world.x,restored.y-world.y,restored.z-world.z).length();
			require(error<Math.max(0.001f,distance*0.00001f),"Reversed-Z world reconstruction error: "+error);
		}
		OriginalStormFogUniforms.write(buffer,projection,view,new Matrix4f(),new Matrix4f(),0,0,0,
				2800,new float[]{1,1,1},new float[0],0,0);
		for(int i=304;i<560;i+=4) require(buffer.getFloat(i)==0,"Stale lightning record after clear");
		try {
			OriginalStormFogUniforms.write(buffer,projection,view,new Matrix4f(),new Matrix4f(),0,0,0,2800,new float[]{1,1,1},bolts,17,0);
			throw new AssertionError("Accepted excess lightning");
		} catch(IllegalArgumentException expected) {}
		System.out.println("PASS original fog std140/16-bolt/reset/matrix ownership and reversed-Z reconstruction");
	}
}

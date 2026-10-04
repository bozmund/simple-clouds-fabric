import dev.nonamecrackers2.simpleclouds.client.renderer.v2.OriginalAtmosphericUniforms;
import org.joml.Matrix2f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import java.nio.*;

public class OriginalAtmosphericUniformsTest {
 static void require(boolean value,String message) { if(!value) throw new AssertionError(message); }
 public static void main(String[] args) {
  ByteBuffer data=ByteBuffer.allocateDirect(208).order(ByteOrder.nativeOrder());
  int cases=0;
  for(float aspect:new float[]{1,16f/9,21f/9}) for(float yaw:new float[]{0,.7f,2})
   for(float pitch:new float[]{-.6f,0,.6f}) {
    Matrix4f projection=new Matrix4f().setPerspective((float)Math.toRadians(70),aspect,30000,.05f,true);
    projection.m20(.02f).m21(-.01f);
    Matrix4f view=new Matrix4f().rotateX(pitch).rotateY(yaw).translate(-123,-70,91);
    Matrix4f beforeP=new Matrix4f(projection),beforeV=new Matrix4f(view);
    Matrix2f transform=new Matrix2f(1,2,3,4);
    OriginalAtmosphericUniforms.write(data,projection,view,transform,.5f,.6f,.1f,.2f,.3f,.4f);
    require(projection.equals(beforeP)&&view.equals(beforeV),"Mutated caller matrices");
    require(data.getFloat(128)==1&&data.getFloat(132)==2&&data.getFloat(144)==3&&data.getFloat(148)==4,"std140 mat2 columns");
    require(data.getFloat(160)==128&&data.getFloat(172)==30000&&data.getFloat(184)==.6f&&data.getFloat(188)==5000&&data.getFloat(204)==.4f,"std140 constants/color");
    Matrix4f inverseP=new Matrix4f().set(0,data),inverseV=new Matrix4f().set(64,data);
    for(float x:new float[]{-.9f,0,.9f}) for(float y:new float[]{-.9f,0,.9f}) {
     Vector4f near=new Vector4f(x,y,1,1).mul(inverseP); near.div(near.w).mul(inverseV);
     Vector4f far=new Vector4f(x,y,0,1).mul(inverseP); far.div(far.w).mul(inverseV);
     Vector3f ray=new Vector3f(far.x-near.x,far.y-near.y,far.z-near.z).normalize();
     Vector4f expected=new Vector4f((x+projection.m20())/projection.m00(),(y+projection.m21())/projection.m11(),-1,0).mul(inverseV);
     require(ray.distance(new Vector3f(expected.x,expected.y,expected.z).normalize())<.0001,"Camera ray differs from analytic projection");
     cases++;
    }
   }
  try { OriginalAtmosphericUniforms.write(ByteBuffer.allocate(207),new Matrix4f(),new Matrix4f(),new Matrix2f(),0,1,1,1,1,1); throw new AssertionError("Accepted small block"); } catch(IllegalArgumentException expected) {}
  try { OriginalAtmosphericUniforms.write(data,new Matrix4f().zero(),new Matrix4f(),new Matrix2f(),0,1,1,1,1,1); throw new AssertionError("Accepted singular camera"); } catch(IllegalArgumentException expected) {}
  System.out.println("PASS atmospheric std140, matrix ownership, invalid inputs and "+cases+" reversed-Z rays");
 }
}

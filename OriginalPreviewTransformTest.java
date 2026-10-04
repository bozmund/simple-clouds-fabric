import dev.nonamecrackers2.simpleclouds.client.renderer.v2.OriginalPreviewTransform;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Independent point arithmetic from original Screen3D bytecode, not runtime UI acceptance. */
public class OriginalPreviewTransformTest {
    static int checks;
    static void close(float a,float b) {
        checks++; if(Math.abs(a-b)>0.0003f) throw new AssertionError(a+" != "+b);
    }
    public static void main(String[] args) {
        for(float rx:new float[]{90,180,225,270}) for(float ry:new float[]{-170,0,45,179})
        for(float zoom:new float[]{1,2,8}) {
            Vector3f offset=new Vector3f(2,-48,3);
            Matrix4f view=OriginalPreviewTransform.view(641,361,zoom,2,1000,rx,ry,offset);
            for(Vector3f p:new Vector3f[]{new Vector3f(),new Vector3f(4,55,-3),new Vector3f(-32,16,32)}) {
                double x=p.x+offset.x,y=p.y+offset.y,z=p.z+offset.z;
                double ax=Math.toRadians(rx),ay=Math.PI+Math.toRadians(ry);
                double rotatedX=Math.cos(ay)*x+Math.sin(ay)*z;
                double rotatedZ=-Math.sin(ay)*x+Math.cos(ay)*z;
                double rotatedY=Math.cos(ax)*y-Math.sin(ax)*rotatedZ;
                double finalZ=Math.sin(ax)*y+Math.cos(ax)*rotatedZ;
                Vector3f actual=view.transformPosition(new Vector3f(p));
                close(actual.x,(float)(320+zoom*2*rotatedX));
                close(actual.y,(float)(180+zoom*2*rotatedY));
                close(actual.z,(float)(-500-finalZ));
                Vector4f modern=OriginalPreviewTransform.projection(640.5f,360.5f,1000)
                    .transform(new Vector4f(actual,1));
                Vector4f old=new Matrix4f().setOrtho(0,640.5f,360.5f,0,0,1000)
                    .transform(new Vector4f(actual,1));
                close(modern.x,old.x);close(modern.y,old.y);
                close(modern.z,1-(old.z+1)/2);close(modern.w,1);
            }
        }
        boolean rejected=false;
        try {OriginalPreviewTransform.projection(0,360,1000);} catch(IllegalArgumentException expected) {rejected=true;}
        if(!rejected) throw new AssertionError("Invalid dimensions accepted");
        System.out.println("PASS original preview transforms: "+checks+" independent point/projection checks; UI validation separate");
    }
}

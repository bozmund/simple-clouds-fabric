import org.joml.Vector3f;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.OriginalPreviewTransform;

public class PreviewCameraTest {
    static void close(float actual,float expected) {
        if(Math.abs(actual-expected)>0.0001f) throw new AssertionError(actual+" != "+expected);
    }
    public static void main(String[] args) {
        var zero=new Vector3f();
        var view=OriginalPreviewTransform.view(640,360,1,.25f,5000,0,0,zero);
        var center=view.transformPosition(new Vector3f());
        close(center.x,320); close(center.y,180); close(center.z,-2500);
        var forward=view.transformDirection(new Vector3f(0,0,1));
        close(forward.x,0); close(forward.y,0); close(forward.z,1);
        var quarter=OriginalPreviewTransform.view(640,360,1,.25f,5000,0,90,zero)
            .transformDirection(new Vector3f(0,0,1));
        close(quarter.x,-.25f); close(quarter.y,0); close(quarter.z,0);
        var zoomed=OriginalPreviewTransform.view(640,360,2,.25f,5000,0,0,zero)
            .transformPosition(new Vector3f(4,0,0));
        close(zoomed.x,318); close(zoomed.y,180); close(zoomed.z,-2500);
        System.out.println("PASS: original preview GUI centering, orthographic scale, rotation and zoom");
    }
}

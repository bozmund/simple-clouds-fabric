import org.joml.Vector3f;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.PreviewDrawPipeline;

public class PreviewCameraTest {
    static void close(float actual,float expected) {
        if(Math.abs(actual-expected)>0.0001f) throw new AssertionError(actual+" != "+expected);
    }
    public static void main(String[] args) {
        var origin=new Vector3f(PreviewDrawPipeline.BOX_CENTER);
        var forward=PreviewDrawPipeline.previewViewMatrix(0,0,1,origin).transformDirection(new Vector3f(0,0,1));
        close(forward.x,0); close(forward.y,0); close(forward.z,-1);
        var quarter=PreviewDrawPipeline.previewViewMatrix(0,90,1,origin).transformDirection(new Vector3f(0,0,1));
        close(quarter.x,-1); close(quarter.y,0); close(quarter.z,0);
        var full=PreviewDrawPipeline.previewViewMatrix(0,360,1,origin).transformDirection(new Vector3f(0,0,1));
        close(full.x,forward.x); close(full.z,forward.z);
        var atCenter=PreviewDrawPipeline.previewViewMatrix(0,0,1,origin).transformPosition(new Vector3f());
        close(atCenter.x,0); close(atCenter.y,0); close(atCenter.z,-110);
        var zoomed=PreviewDrawPipeline.previewViewMatrix(0,0,2,origin).transformPosition(new Vector3f());
        close(zoomed.z,-55);
        System.out.println("PASS: preview half-turn, quarter/full rotations, centered translation and zoom");
    }
}

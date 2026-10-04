import dev.nonamecrackers2.simpleclouds.client.renderer.v2.IndexedCloudBlend;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.OitMrtParityProbe;
public final class IndexedCloudBlendDisabledTest {
    public static void main(String[] args) {
        if(IndexedCloudBlend.enabled() || IndexedCloudBlend.scoped())throw new AssertionError("Indexed blend unexpectedly active");
        for(int i=0;i<100;i++) {
            IndexedCloudBlend.afterBind(null);
            if(OitMrtParityProbe.begin()!=null)throw new AssertionError("Unexpected readback fixture");
        }
        System.out.println("PASS indexed blend/parity inert without developer and MRT opt-ins");
    }
}

import dev.nonamecrackers2.simpleclouds.client.DhFogLifecycleProbe;
import dev.nonamecrackers2.simpleclouds.client.compat.ImmersiveDhFogLifecycle;

public final class DhFogLifecycleDisabledTest {
    public static void main(String[] args) throws Exception {
        if("1".equals(System.getenv("SIMPLECLOUDS_DEV"))
            && "1".equals(System.getenv("SIMPLECLOUDS_TEST_DH_FOG_LIFECYCLE")))
            throw new AssertionError("This test requires an incomplete opt-in");
        for(int i=0;i<100;i++) {
            DhFogLifecycleProbe.tick(null);
            DhFogLifecycleProbe.resourcesReloaded();
            ImmersiveDhFogLifecycle.tick(null);
            ImmersiveDhFogLifecycle.release();
        }
        var ticks=DhFogLifecycleProbe.class.getDeclaredField("ticks");ticks.setAccessible(true);
        if(ticks.getInt(null)!=0)throw new AssertionError("Probe advanced without opt-in");
        var reload=DhFogLifecycleProbe.class.getDeclaredField("reloadState");reload.setAccessible(true);
        if(reload.get(null)!=null)throw new AssertionError("Resource reload initialized without fixture opt-in");
        for(String name:new String[]{"config","world","slots","previous","applied","owned","get","set","clear"}) {
            var field=ImmersiveDhFogLifecycle.class.getDeclaredField(name);field.setAccessible(true);
            if(field.get(null)!=null)throw new AssertionError("Optional DH state initialized: "+name);
        }
        System.out.println("PASS: DH fog probe and unbound ownership helper stay inert without optional libraries or fixture opt-in");
    }
}

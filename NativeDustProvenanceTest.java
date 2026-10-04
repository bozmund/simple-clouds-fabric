import dev.nonamecrackers2.simpleclouds.client.compat.NativeDustProvenance;
import net.minecraft.core.particles.ParticleOptions;

public final class NativeDustProvenanceTest {
    private static ParticleOptions options() {
        return (ParticleOptions)java.lang.reflect.Proxy.newProxyInstance(
            ParticleOptions.class.getClassLoader(),new Class<?>[]{ParticleOptions.class},(proxy,method,args) ->
            switch(method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy==args[0];
                case "toString" -> "fixture-options";
                default -> null;
            });
    }
    public static void main(String[] args) throws Exception {
        var ambient=options();var storm=options();
        if(NativeDustProvenance.isAmbient(ambient))throw new AssertionError("unknown producer marked ambient");
        NativeDustProvenance.markAmbient(ambient);
        NativeDustProvenance.markAmbient(ambient);
        if(!NativeDustProvenance.isAmbient(ambient) || NativeDustProvenance.isAmbient(storm))
            throw new AssertionError("producer identity leaked");
        var thread=new Thread(() -> {
            if(!NativeDustProvenance.isAmbient(ambient))throw new AssertionError("queued creation lost provenance");
        });
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        thread.setUncaughtExceptionHandler((t,e)->failure.set(e));thread.start();thread.join();
        if(failure.get()!=null)throw new AssertionError(failure.get());
        NativeDustProvenance.markAmbient(null);
        if(NativeDustProvenance.isAmbient(null))throw new AssertionError("null producer marked");
        System.out.println("PASS NativeDustProvenance identity/idempotence/queued-thread/unknown/null");
    }
}

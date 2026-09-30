import net.minecraftforge.common.MinecraftForge.SimpleEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import dev.nonamecrackers2.simpleclouds.api.common.event.CloudRegionTickEvent;

public class EventBusTest {
    static int statics;
    public static class StaticListener {
        @SubscribeEvent public static void event(CloudRegionTickEvent event) { statics++; }
    }
    public static class Listener {
        int called;
        @SubscribeEvent public void event(CloudRegionTickEvent event) { called++; event.setModifiedMaxSpeed(2); }
    }
    public static class Failing {
        @SubscribeEvent public void event(Object event) { throw new IllegalStateException("expected"); }
    }
    public static class Invalid {
        @SubscribeEvent public int event(Object event) { return 1; }
    }
    public static class Reentrant {
        final SimpleEventBus bus;
        int called;
        Reentrant(SimpleEventBus bus) { this.bus=bus; }
        @SubscribeEvent public void event(dev.nonamecrackers2.simpleclouds.api.Event event) {
            called++; bus.unregister(this);
        }
    }
    public static void main(String[] args) {
        var bus=new SimpleEventBus(); var listener=new Listener();
        bus.register(listener); bus.register(listener); bus.register(StaticListener.class);
        var event=new CloudRegionTickEvent(null,null); bus.post(event);
        if(listener.called!=1 || statics!=1 || event.getModifiedMaxSpeed()!=2) throw new AssertionError("dispatch/mutation");
        bus.post("unrelated");
        if(listener.called!=1 || statics!=1) throw new AssertionError("type filter");
        bus.unregister(listener); bus.unregister(StaticListener.class); bus.post(event);
        if(listener.called!=1 || statics!=1) throw new AssertionError("unregister");
        var reentrant=new Reentrant(bus); bus.register(reentrant); bus.post(event); bus.post(event);
        if(reentrant.called!=1) throw new AssertionError("base event dispatch/self-unregistration");
        try { bus.register(new Invalid()); throw new AssertionError("invalid signature accepted"); }
        catch(IllegalArgumentException expected) {}
        bus.register(new Failing());
        try { bus.post(event); throw new AssertionError("exception swallowed"); }
        catch(IllegalStateException expected) { if(!expected.getMessage().equals("expected")) throw expected; }
        System.out.println("PASS: actual cloud event mutation, static/instance dispatch, deduplication, filtering, unregister, invalid handler and exception propagation");
    }
}

package net.minecraftforge.registries;
public class DeferredRegister<T> {
    public static <T> DeferredRegister<T> create(net.minecraft.core.Registry<T> registry) { return new DeferredRegister<>(); }
    public <V extends T> RegistryObject<V> register(String name, java.util.function.Supplier<V> supplier) { 
        return new RegistryObject<>(); 
    }
    public void register(net.minecraftforge.eventbus.api.IEventBus bus) {}
}

package net.minecraftforge.eventbus.api;
public interface IEventBus {
    void addListener(Class<?> eventClass, Object listener);
    void register(Object target);
    void post(Object event);
}

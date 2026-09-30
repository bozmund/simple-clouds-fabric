package net.minecraftforge.common;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Synchronous compatibility bus for this port's API events, not a full Forge bus. */
public class MinecraftForge {
    public static final IForgeEventBus EVENT_BUS = new SimpleEventBus();

    public interface IForgeEventBus {
        void post(Object event);
        void register(Object target);
        void unregister(Object target);
    }

    public static final class SimpleEventBus implements IForgeEventBus {
        private record Handler(Object owner, Object receiver, Method method, Class<?> eventType) {}
        private final CopyOnWriteArrayList<Handler> handlers = new CopyOnWriteArrayList<>();

        @Override
        public synchronized void register(Object target) {
            Objects.requireNonNull(target, "listener");
            if (handlers.stream().anyMatch(h -> h.owner() == target)) return;
            boolean staticOnly = target instanceof Class<?>;
            Class<?> type = staticOnly ? (Class<?>)target : target.getClass();
            var additions = new ArrayList<Handler>();
            for (Method method : type.getMethods()) {
                if (!method.isAnnotationPresent(SubscribeEvent.class)) continue;
                if (Modifier.isStatic(method.getModifiers()) != staticOnly) continue;
                if (method.getParameterCount() != 1 || method.getReturnType() != void.class
                        || method.getParameterTypes()[0].isPrimitive())
                    throw new IllegalArgumentException("Event handler must be public void with one event parameter: " + method);
                if (!method.trySetAccessible())
                    throw new IllegalArgumentException("Inaccessible event handler: " + method);
                additions.add(new Handler(target, staticOnly ? null : target, method, method.getParameterTypes()[0]));
            }
            handlers.addAll(additions);
        }

        @Override
        public synchronized void unregister(Object target) {
            handlers.removeIf(h -> h.owner() == target);
        }

        @Override
        public void post(Object event) {
            Objects.requireNonNull(event, "event");
            // Snapshot iteration permits registration/removal from inside callbacks.
            for (Handler handler : handlers) {
                if (!handler.eventType().isInstance(event)) continue;
                try {
                    handler.method().invoke(handler.receiver(), event);
                } catch (InvocationTargetException failure) {
                    Throwable cause = failure.getCause();
                    if (cause instanceof RuntimeException runtime) throw runtime;
                    if (cause instanceof Error error) throw error;
                    throw new IllegalStateException("Event handler failed: " + handler.method(), cause);
                } catch (IllegalAccessException failure) {
                    throw new IllegalStateException("Cannot invoke event handler: " + handler.method(), failure);
                }
            }
        }
    }
}

package dev.nonamecrackers2.simpleclouds.client.compat;

import java.lang.reflect.Method;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig;

/** Own only observed writes by the pinned weather integration, not all DH overrides. */
public final class ImmersiveDhFogLifecycle {
    private static Object config,world;
    private static Object[] slots,previous,applied;
    private static boolean[] owned;
    private static Method get,set,clear;
    private ImmersiveDhFogLifecycle() {}

    public static boolean beforeWrite(float reduction) {
        if(reduction>=.99f) { release();return true; }
        try {
            Object current=Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed").getField("configs").get(null);
            if(current==null)return false;
            if(current!=config) {
                release();config=current;
                Object graphics=call(config,"graphics"),fog=call(graphics,"fog"),far=call(fog,"farFog");
                slots=new Object[]{call(far,"farFogMinThickness"),call(far,"farFogMaxThickness"),call(fog,"enableVanillaFog")};
                previous=new Object[3];applied=new Object[3];owned=new boolean[3];
                Class<?> api=Class.forName("com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue");
                get=api.getMethod("getApiValue");set=api.getMethod("setValue",Object.class);clear=api.getMethod("clearValue");
            }
            world=Minecraft.getInstance().level;
            for(int i=0;i<3;i++) {
                Object value=get.invoke(slots[i]);
                if(!owned[i] || !Objects.equals(value,applied[i])) {
                    previous[i]=value;owned[i]=false;
                }
            }
            return false;
        } catch(ReflectiveOperationException e) {throw new IllegalStateException("Pinned DH fog ownership contract changed",e);}
    }
    public static void afterWrite() {
        if(slots==null)return;
        try {
            for(int i=0;i<3;i++) {
                applied[i]=get.invoke(slots[i]);
                owned[i]=!Objects.equals(applied[i],previous[i]);
            }
        } catch(ReflectiveOperationException e) {throw new IllegalStateException("Cannot record DH weather overrides",e);}
    }
    public static void release() {
        if(slots==null)return;
        try {
            for(int i=0;i<3;i++) {
                if(owned[i] && Objects.equals(get.invoke(slots[i]),applied[i])) {
                    if(previous[i]==null)clear.invoke(slots[i]);else set.invoke(slots[i],previous[i]);
                    if(!Objects.equals(get.invoke(slots[i]),previous[i]))
                        throw new IllegalStateException("Failed to restore owned DH weather override "+i);
                }
                owned[i]=false;
            }
            world=null;
        } catch(ReflectiveOperationException e) {throw new IllegalStateException("Cannot release DH weather overrides",e);}
    }
    public static void tick(Minecraft mc) {
        if(slots!=null && (mc.level==null || mc.level!=world || !SimpleCloudsConfig.CLIENT.biomeStormEffects.get()))release();
    }
    private static Object call(Object target,String name) throws ReflectiveOperationException {
        return target.getClass().getMethod(name).invoke(target);
    }
}

package dev.nonamecrackers2.simpleclouds.client.compat;

import dev.nonamecrackers2.simpleclouds.common.world.LocalWeatherEffects;
import dev.nonamecrackers2.simpleclouds.common.world.RecentWetColumns;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/** Optional upstream particle rule adapter; no Particle Rain class linkage. */
public final class ParticleWeatherBridge {
    private static final RecentWetColumns recentWet=new RecentWetColumns(4096,6000);
    private ParticleWeatherBridge() {}
    public static synchronized boolean matches(ClientLevel level,BlockPos pos,Object rule,LocalWeatherEffects.Sample sample) {
        // A queued old-world producer must not rebind current-world history.
        if(level==null || net.minecraft.client.Minecraft.getInstance().level!=level)return false;
        long column=BlockPos.asLong(pos.getX(),0,pos.getZ());
        boolean recentlyWet=recentWet.observe(level,level.getGameTime(),column,sample.rain()>0);
        return sample.matches(LocalWeatherEffects.Condition.valueOf(((Enum<?>)rule).name()),recentlyWet);
    }
    public static synchronized void tick(ClientLevel level) {recentWet.bindOwner(level);}
    public static synchronized void clear() {recentWet.clear();}
}

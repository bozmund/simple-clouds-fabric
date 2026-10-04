package dev.nonamecrackers2.simpleclouds.common.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Shared local authority for effects, not a second weather engine.
 * Biome/roof/collision rules belong to each effect and must still be applied.
 */
public final class LocalWeatherEffects {
    public enum Condition {
        DURING_WEATHER, ONLY_DURING_NORMAL_WEATHER, ONLY_DURING_STORMY_WEATHER,
        AFTER_WEATHER, CLEAR, ALWAYS
    }
    public record Sample(float rain, float thunder, float windX, float windZ, boolean localAuthority) {
        public Sample {
            rain=unit(rain);
            thunder=Math.min(rain,unit(thunder));
            if(!Float.isFinite(windX)) windX=0;
            if(!Float.isFinite(windZ)) windZ=0;
        }
        public boolean matches(Condition condition, boolean recentlyWet) {
            return switch(condition) {
                case DURING_WEATHER -> rain>0;
                case ONLY_DURING_NORMAL_WEATHER -> rain>0 && thunder==0;
                case ONLY_DURING_STORMY_WEATHER -> rain>0 && thunder>0;
                case AFTER_WEATHER -> rain==0 && recentlyWet;
                case CLEAR -> rain==0;
                case ALWAYS -> true;
            };
        }
    }
    private LocalWeatherEffects() {}
    private static float unit(float value) {
        return Float.isFinite(value)?Math.max(0,Math.min(1,value)):0;
    }
    public static Sample at(Level level, BlockPos pos) {
        if(level instanceof CloudManagerHolder<?> holder) {
            var manager=holder.getCloudManager();
            if(manager!=null && !manager.shouldUseVanillaWeather()) {
                var cloud=manager.getCloudTypeAtWorldPos(pos.getX()+.5f,pos.getZ()+.5f);
                float rain=CloudManager.calculateRainLevel(cloud.getLeft(),cloud.getRight(),pos.getY()+.5f,manager.getCloudHeight());
                var wind=manager.calculateWindDirection();
                float thunder=cloud.getLeft().weatherType().includesThunder()?rain:0;
                return new Sample(rain,thunder,wind.x,wind.y,true);
            }
        }
        return new Sample(level.getRainLevel(1),level.getThunderLevel(1),0,0,false);
    }
}

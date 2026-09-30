import java.util.HashMap;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.util.valueproviders.ConstantFloat;
import dev.nonamecrackers2.simpleclouds.client.compat.SimpleCloudsSoundReplacements;

public class RainSoundTest {
    static Identifier id(String s) { return Identifier.parse(s); }
    static Sound sound(int index) {
        return new Sound(id("minecraft:ambient/weather/rain"+index),ConstantFloat.of(.7f),
            ConstantFloat.of(1.1f),3,Sound.Type.FILE,false,true,24);
    }
    static void require(boolean ok) { if(!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        var cache=new HashMap<Identifier,Resource>();
        for(int i=1;i<=8;i++) cache.put(id("simpleclouds:sounds/ambient/weather/rain"+i+".ogg"),null);
        for(int i=1;i<=9;i++) {
            Sound input=sound(i);
            for(String event:new String[]{"weather.rain","weather.rain.above","other"}) {
                var result=SimpleCloudsSoundReplacements.applyReplacement(input,id("minecraft:"+event),cache,true);
                boolean replace=event.equals("weather.rain")&&i<=8 || event.equals("weather.rain.above")&&i<=4;
                require((result!=input)==replace);
                if(replace) {
                    Sound out=(Sound)result;
                    require(out.getLocation().getNamespace().equals("simpleclouds"));
                    require(out.getVolume()==input.getVolume() && out.getPitch()==input.getPitch());
                    require(out.getWeight()==3 && out.shouldPreload() && out.getAttenuationDistance()==24);
                }
                require(SimpleCloudsSoundReplacements.applyReplacement(input,id("minecraft:"+event),cache,false)==input);
            }
        }
        require(SimpleCloudsSoundReplacements.applyReplacement(sound(1),id("other:weather.rain"),cache,true).getSound(net.minecraft.util.RandomSource.create()).getLocation().getNamespace().equals("minecraft"));
        Sound missing=sound(1);
        require(SimpleCloudsSoundReplacements.applyReplacement(missing,id("minecraft:weather.rain"),java.util.Map.of(),true)==missing);
        System.out.println("PASS: rain/above mappings, disabled/unrelated/missing fallback and sound properties");
    }
}

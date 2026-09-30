import dev.nonamecrackers2.simpleclouds.client.renderer.WorldEffects;
import java.awt.Color;

public class WeatherStateTest {
    private static void set(WorldEffects effects,String name,float value) throws Exception {
        var field=WorldEffects.class.getDeclaredField(name);
        field.setAccessible(true); field.setFloat(effects,value);
    }
    private static void near(float a,float b) {
        if(Math.abs(a-b)>.00001F) throw new AssertionError(a+" != "+b);
    }
    public static void main(String[] args) throws Exception {
        WorldEffects effects=new WorldEffects(null,null);
        near(effects.getDarkenFactor(.5F),1);
        set(effects,"storminessSmoothedO",.2F);
        set(effects,"storminessSmoothed",.6F);
        near(effects.getStorminessSmoothed(0),.2F);
        near(effects.getStorminessSmoothed(.5F),.4F);
        near(effects.getStorminessSmoothed(1),.6F);
        near(effects.getDarkenFactor(.5F),.52F);
        set(effects,"storminessSmoothedO",1);
        set(effects,"storminessSmoothed",1);
        near(effects.getDarkenFactor(.5F),.1F);
        Color storm=effects.calculateSkyColor(.5F,.7F,1,.5F);
        if(storm.getBlue()>=255) throw new AssertionError("Storm sky unchanged");
        effects.reset();
        near(effects.getStorminessSmoothed(.5F),0);
        near(effects.getFadeRegionAtCamera(),1);
        if(effects.getCloudTypeAtCamera()!=null || !effects.getLightningBolts().isEmpty())
            throw new AssertionError("Weather leaked across level reset");
        Color clear=effects.calculateSkyColor(.5F,.7F,1,.5F);
        if(clear.getBlue()!=255 || Math.abs(clear.getRed()-127)>1)
            throw new AssertionError("Clear sky color was not preserved");
        System.out.println("PASS: original weather interpolation, storm darkening without lightning, color and reset");
    }
}

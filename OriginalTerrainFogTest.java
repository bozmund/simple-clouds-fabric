import dev.nonamecrackers2.simpleclouds.client.renderer.v2.OriginalTerrainFog;
import net.minecraft.client.renderer.fog.FogData;
import org.joml.Vector4f;

public class OriginalTerrainFogTest {
 static void require(boolean ok,String label) { if(!ok) throw new AssertionError(label); }
 static FogData fixture() {
  FogData fog=new FogData(); fog.environmentalStart=32; fog.environmentalEnd=160;
  fog.renderDistanceStart=192; fog.renderDistanceEnd=256; fog.skyEnd=512; fog.cloudEnd=10240;
  fog.color=new Vector4f(.1f,.2f,.3f,.4f); return fog;
 }
 public static void main(String[] args) {
  for(int i=0;i<=1000;i++) {
   float darken=.1f+.9f*i/1000; FogData fog=fixture();
   OriginalTerrainFog.apply(fog,false,darken);
   float factor=(float)Math.sqrt(darken);
   require(fog.environmentalStart==32*factor && fog.renderDistanceStart==192*factor,"Original square-root start scaling");
   require(fog.environmentalEnd==160 && fog.renderDistanceEnd==256,"Storm changed fog end");
   require(fog.skyEnd==512 && fog.cloudEnd==10240 && fog.color.equals(new Vector4f(.1f,.2f,.3f,.4f)),"Changed sky/cloud/color policy");
  }
  FogData off=fixture(); OriginalTerrainFog.apply(off,true,1);
  require(Float.isFinite(off.environmentalEnd)&&off.environmentalStart<off.environmentalEnd&&off.renderDistanceStart<off.renderDistanceEnd,"Disabled edges invalid");
  for(float distance:new float[]{0,32,256,10000,1000000}) require(distance<off.environmentalStart&&distance<off.renderDistanceStart,"OFF still fogs finite world distance");
  require(off.skyEnd==512&&off.cloudEnd==10240,"OFF changed independent distance policy");
  require(off.color.equals(new Vector4f(.1f,.2f,.3f,.4f)),"OFF changed color");
  FogData clear=fixture(); OriginalTerrainFog.apply(clear,false,1);
  require(clear.environmentalStart==32&&clear.renderDistanceStart==192,"Clear weather changed starts");
  for(float invalid:new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,-.1f,1.1f}) {
   FogData fog=fixture(); boolean rejected=false;
   try { OriginalTerrainFog.apply(fog,false,invalid); } catch(IllegalArgumentException expected) { rejected=true; }
   require(rejected&&fog.environmentalStart==32&&fog.renderDistanceStart==192,"Invalid factor mutated fog");
  }
  System.out.println("PASS 1001 original terrain fog scaling cases, finite OFF ranges, sky/cloud/color ownership; runtime separate");
 }
}

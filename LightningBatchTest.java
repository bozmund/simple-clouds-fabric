import dev.nonamecrackers2.simpleclouds.client.renderer.lightning.LightningBolt;
import net.minecraft.util.RandomSource;
import org.joml.Vector3f;
import java.util.Arrays;

public class LightningBatchTest {
    public static void main(String[] args) {
        LightningBolt[] bolts = new LightningBolt[3];
        for(int i=0;i<bolts.length;i++)
            bolts[i]=new LightningBolt(RandomSource.create(100+i),new Vector3f(i*200,300,0),
                8,4,20,6,5,80,1,.7F,.5F);
        int checks=0, largest=0;
        for(int tick=0;tick<61;tick++) {
            for(LightningBolt bolt:bolts) bolt.tick();
            for(float partial:new float[]{0,.5F,1}) {
                float[][] separate=new float[bolts.length][];
                int[] lengths=new int[bolts.length];
                int capacity=0;
                for(int i=0;i<bolts.length;i++) {
                    separate[i]=new float[bolts[i].renderFloatCount(partial)];
                    lengths[i]=bolts[i].renderInto(separate[i],partial,1,1,1,1);
                    if(lengths[i]!=0 && lengths[i]!=separate[i].length)
                        throw new AssertionError("Incorrect capacity calculation");
                    capacity+=separate[i].length;
                    largest=Math.max(largest,lengths[i]);
                }
                float[] combined=new float[capacity+14];
                Arrays.fill(combined,Float.NaN);
                int offset=7;
                for(int i=0;i<bolts.length;i++) {
                    int count=bolts[i].renderInto(combined,offset,partial,1,1,1,1);
                    if(count!=lengths[i]) throw new AssertionError("Batch count mismatch");
                    for(int f=0;f<count;f++) {
                        if(combined[offset+f]!=separate[i][f] || !Float.isFinite(combined[offset+f]))
                            throw new AssertionError("Corrupt appended geometry");
                        checks++;
                    }
                    offset+=count;
                }
                for(int f=0;f<7;f++) if(!Float.isNaN(combined[f])) throw new AssertionError("Prefix overwritten");
                for(int f=offset;f<combined.length;f++) if(!Float.isNaN(combined[f])) throw new AssertionError("Suffix overwritten");
            }
        }
        if(checks==0) throw new AssertionError("No visible bolts tested");
        System.out.println("PASS: "+checks+" lightning float comparisons over full lifetimes, largest bolt="+largest+" floats; three bolts append without overwriting");
    }
}

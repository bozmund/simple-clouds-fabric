import dev.nonamecrackers2.simpleclouds.client.renderer.v2.CloudRefreshSchedule;
import java.util.*;

public class CloudRefreshScheduleTest {
    record Chunk(int id,int lod,long tick,boolean visible) {}
    static List<Chunk> select(List<Chunk> chunks,int budget) {
        return CloudRefreshSchedule.select(chunks,budget,Chunk::tick,Chunk::visible);
    }
    static void check(boolean valid,String error) { if(!valid) throw new AssertionError(error); }
    public static void main(String[] args) {
        List<Chunk> chunks=new ArrayList<>();
        // Recorded workload: 28 near (12 visible), 252 distant (103 visible).
        for(int i=0;i<28;i++) chunks.add(new Chunk(i,1,100,i<12));
        for(int i=0;i<252;i++) chunks.add(new Chunk(28+i,4,100,i<103));
        var before=List.copyOf(chunks);
        var selected=select(chunks,96);
        check(selected.size()==96,"refresh budget changed");
        check(selected.stream().allMatch(Chunk::visible),"offscreen near quota displaced visible work");
        check(selected.stream().anyMatch(c -> c.lod()==1) && selected.stream().anyMatch(c -> c.lod()==4),"lost a visible ring");
        check(chunks.equals(before),"mutated caller order");
        Chunk neglected=new Chunk(900,8,0,false),recent=new Chunk(901,1,100,true);
        check(select(List.of(recent,neglected),1).getFirst()==neglected,"offscreen work starved");
        check(select(chunks,0).isEmpty() && select(chunks,-1).isEmpty(),"negative budget accepted");
        check(select(List.of(recent),10).size()==1,"invented work");
        Chunk extreme=new Chunk(902,1,Long.MIN_VALUE,true);
        check(select(List.of(recent,extreme),1).getFirst()==extreme,"age priority overflow");
        // Same generation age and visibility: retain the caller's distance order.
        check(select(List.of(chunks.get(4),chunks.get(1)),1).getFirst()==chunks.get(4),"unstable ties");
        System.out.println("PASS: unified LOD budget favors visible work, ages offscreen work, preserves ties and input");
    }
}

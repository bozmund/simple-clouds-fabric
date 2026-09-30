import dev.nonamecrackers2.simpleclouds.client.mesh.LevelOfDetailOptions;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.CloudWorldCoverage;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.CloudWorldCoverage.Rect;
import java.util.*;

/** Regression originally reproduced as a failing negative control: actual
 * prepared LOD rings retain shared physical area through partial publication. */
public class CloudMixedLodCoverageProbe {
    record Key(int x, int z, int lod) {}
    static final class Source {
        final int lod; boolean closed;
        Source(int lod) { this.lod=lod; }
    }
    static Map<Key,Rect> layout(LevelOfDetailOptions option,int dx,int dz) {
        Map<Key,Rect> result=new HashMap<>();
        for (var chunk:option.getConfig().getPreparedChunks()) {
            int span=32*chunk.lodScale(), x=dx+chunk.x()*span,z=dz+chunk.z()*span;
            result.put(new Key(x,z,chunk.lodScale()),new Rect(x,z,x+span,z+span,chunk.lodScale()));
        }
        return result;
    }
    static long cell(int x,int z) { return ((long)x<<32)|(z&0xffffffffL); }
    static Map<Long,Integer> raster(Collection<Rect> rectangles,boolean lodValues) {
        Map<Long,Integer> result=new HashMap<>();
        for (Rect rect:rectangles) for(int x=rect.x0();x<rect.x1();x+=8)
            for(int z=rect.z0();z<rect.z1();z+=8)
                result.merge(cell(x,z),lodValues?rect.lod():1,(a,b)->lodValues?-1:a+b);
        return result;
    }
    public static void main(String[] args) {
        long totalMissing=0;
        for(var option:LevelOfDetailOptions.values()) for(int[] shift:new int[][]{{32,0},{-32,0},{0,32},{32,32}}) {
            var old=layout(option,0,0); var next=layout(option,shift[0],shift[1]);
            List<Source> sources=new ArrayList<>();
            var coverage=new CloudWorldCoverage<Key,Source>(source -> {
                if(source.closed) throw new AssertionError("source closed twice");
                source.closed=true;
            });
            coverage.retarget(old); old.keySet().forEach(k -> {
                Source source=new Source(k.lod()); sources.add(source); coverage.publish(k,source);
            });
            var oldCells=raster(old.values(),true);
            var nextCells=raster(next.values(),true);
            coverage.retarget(next);
            var actual=raster(coverage.snapshot().values().stream().flatMap(List::stream)
                .map(CloudWorldCoverage.Fragment::bounds).toList(),false);
            long shared=0,changedLod=0,missing=0,duplicates=0;
            for(var entry:oldCells.entrySet()) {
                Integer newLod=nextCells.get(entry.getKey()); if(newLod==null) continue;
                if(entry.getValue()<0 || newLod<0) throw new AssertionError("prepared rings overlap");
                shared++; if(!entry.getValue().equals(newLod)) changedLod++;
                int count=actual.getOrDefault(entry.getKey(),0);
                if(count==0) missing++; if(count>1) duplicates++;
            }
            System.out.printf("%s shift=%d,%d shared=%d changedLOD=%d missing=%d duplicate=%d missingArea=%d cloudUnitsSquared%n",
                option,shift[0],shift[1],shared,changedLod,missing,duplicates,missing*64);
            totalMissing+=missing;
            if(missing==0 && duplicates==0) {
                // Publish several targets at every LOD without completing the
                // entire layout; pending targets must keep their old sources.
                var ordered=new ArrayList<>(next.keySet());
                ordered.sort(Comparator.comparingInt(Key::lod).thenComparingInt(Key::x).thenComparingInt(Key::z));
                Map<Integer,Integer> perLod=new HashMap<>();
                int publications=0;
                for(Key key:ordered) {
                    if(perLod.merge(key.lod(),1,Integer::sum)>4) continue;
                    Source source=new Source(key.lod()); sources.add(source); coverage.publish(key,source);
                    var fragments=coverage.snapshot().values().stream().flatMap(List::stream).toList();
                    for(var fragment:fragments)
                        if(fragment.source().closed || fragment.source().lod!=fragment.bounds().lod())
                            throw new AssertionError("retained source closed or voxel lattice reinterpreted");
                    var counts=raster(fragments.stream().map(CloudWorldCoverage.Fragment::bounds).toList(),false);
                    for(long cell:oldCells.keySet()) if(nextCells.containsKey(cell)
                        && counts.getOrDefault(cell,0)!=1)
                        throw new AssertionError("partial publication lost or duplicated shared cell");
                    publications++;
                }
                System.out.println("  PASS partial publications="+publications+" with original source LOD and live owners");
            }
            coverage.close();
            if(sources.stream().anyMatch(source -> !source.closed)) throw new AssertionError("source owner leaked");
        }
        if(totalMissing>0) throw new AssertionError("mixed-LOD retarget loses "+totalMissing+" already-visible sample cells");
        System.out.println("PASS: mixed-LOD shared area retained before and during partial publication; owners release once");
    }
}

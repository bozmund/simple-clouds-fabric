import java.util.Arrays;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.StormCoverage;

public class StormCoverageTest {
    static void check(float actual, float expected) {
        if (actual != expected) throw new AssertionError(actual + " != " + expected);
    }
    public static void main(String[] args) {
        boolean[] full = new boolean[32*32];
        Arrays.fill(full, true);
        for (int lod : new int[]{1,2,4,8}) {
            check(StormCoverage.contribution(full,32,32,1000,1000,lod,0,0),0);
            check(StormCoverage.contribution(full,32,32,-16,-16,lod,0,0),1);
            // Four adjacent chunks must cover exactly once, including negative coordinates.
            float sum=0;
            for(int x:new int[]{-32*lod,0}) for(int z:new int[]{-32*lod,0})
                sum += StormCoverage.contribution(full,32,32,x,z,lod,0,0);
            check(sum,1);
            check(StormCoverage.contribution(full,32,32,0,0,lod,0,0),0.25f);
        }
        boolean[] half = new boolean[8*8];
        Arrays.fill(half,0,32,true);
        check(StormCoverage.contribution(half,8,8,-4,-4,1,0,0),0.5f);
        check(StormCoverage.contribution(full,32,32,0,0,1,Float.NaN,0),0);
        System.out.println("PASS: distant storms, four LODs, chunk boundaries, partial coverage, invalid camera");
    }
}

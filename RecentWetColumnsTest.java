import dev.nonamecrackers2.simpleclouds.common.world.RecentWetColumns;
public class RecentWetColumnsTest {
    static void check(boolean c,String m) {if(!c) throw new AssertionError(m);}
    public static void main(String[] args) {
        var h=new RecentWetColumns(2,10); Object world=new Object(),other=new Object();
        check(!h.observe(world,0,1,false),"invented history");
        check(!h.observe(world,1,1,true),"wet simultaneously after-weather");
        check(h.observe(world,2,1,false),"local storm exit forgotten");
        check(!h.observe(world,2,2,false),"history leaked across columns");
        check(h.observe(world,11,1,false)&&!h.observe(world,12,1,false),"expiry boundary");
        h.observe(world,13,1,true);h.observe(world,13,2,true);h.observe(world,13,3,true);
        check(h.size()==2&&!h.observe(world,14,1,false),"bounded history/oldest retention");
        check(!h.observe(other,14,2,false)&&h.size()==0,"history leaked into next world");
        h.observe(other,20,1,true);
        check(!h.observe(other,19,1,false),"clock rollback retained stale history");
        h.observe(other,21,1,true);h.clear();
        check(h.size()==0&&!h.observe(other,22,1,false),"disconnect clear");
        h.observe(world,30,4,true);h.bindOwner(world);
        check(h.size()==1&&h.observe(world,31,4,false),"same-world tick cleared valid history");
        h.bindOwner(other);
        check(h.size()==0&&!h.observe(other,31,4,false),"dimension with no queries retained old history");
        h.observe(other,32,4,true);h.bindOwner(null);
        check(h.size()==0&&!h.observe(world,33,4,false),"null-world tick retained history");
        System.out.println("PASS local after-weather history: owner/column isolation, expiry, cap, clock rollback, eager world binding and disconnect");
    }
}

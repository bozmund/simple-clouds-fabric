import dev.nonamecrackers2.simpleclouds.client.renderer.v2.*;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.CloudWorldCoverage.*;
import java.nio.*;
import java.util.*;

public class CloudFaceCoverageTest {
    static byte[] mesh(Rect bounds, int stride) {
        int cells = (bounds.x1()-bounds.x0()) / bounds.lod() * (bounds.z1()-bounds.z0()) / bounds.lod();
        ByteBuffer data = ByteBuffer.allocate(cells * stride).order(ByteOrder.nativeOrder());
        for (int x = bounds.x0(); x < bounds.x1(); x += bounds.lod())
            for (int z = bounds.z0(); z < bounds.z1(); z += bounds.lod()) {
                data.putFloat(2).putFloat((x + bounds.lod()*.5F)*8).putFloat(128)
                    .putFloat((z + bounds.lod()*.5F)*8).putFloat(bounds.lod()*4).putFloat(.7F);
                if (stride == 28) data.putFloat(.3F);
            }
        return data.array();
    }
    static void check(boolean yes, String why) { if (!yes) throw new AssertionError(why); }
    public static void main(String[] args) {
        int stable = 0;
        for (int lod : new int[] {1,2,4,8}) for (int stride : new int[] {24,28})
            for (int base : new int[] {-64,0}) {
                int span = 32 * lod, shift = lod == 1 ? 2 : 32;
                Rect a = new Rect(base, base, base + span, base + span, lod);
                Rect b = new Rect(base + span, base, base + 2*span, base + span, lod);
                Rect target = new Rect(base + shift, base, base + shift + span, base + span, lod);
                var sources = new ArrayList<Fragment<byte[]>>(List.of(
                    new Fragment<>(a.intersection(target), mesh(a,stride)),
                    new Fragment<>(b.intersection(target), mesh(b,stride))));
                Collections.shuffle(sources, new Random(31 + lod));
                byte[] before = CloudFaceCoverage.gather(sources, stride);
                byte[] after = mesh(target,stride);
                var delta = CloudFaceDelta.split(before, ByteBuffer.wrap(after), stride);
                check(delta.stableCount(stride)==1024, "same-phase world faces not stable");
                check(delta.added().length==0 && delta.removed().length==0, "invented relocation delta");
                check(Arrays.equals(delta.complete(),after), "changed next records");
                stable += delta.stableCount(stride);
            }
        Rect cell = new Rect(-2,-2,0,0,1);
        byte[] record = Arrays.copyOf(mesh(cell,28),28);
        byte[] duplicates = new byte[56];
        System.arraycopy(record,0,duplicates,0,28); System.arraycopy(record,0,duplicates,28,28);
        check(Arrays.equals(CloudFaceCoverage.gather(List.of(new Fragment<>(cell,duplicates)),28),duplicates),
            "deduplicated legitimate group faces");
        check(CloudFaceCoverage.gather(List.of(),24).length==0, "nonempty missing predecessor");
        try {
            CloudFaceCoverage.gather(List.of(new Fragment<>(cell,new byte[25])),24);
            throw new AssertionError("accepted malformed face");
        } catch (IllegalArgumentException expected) {}
        System.out.println("PASS: " + stable + " same-phase faces stable across two predecessor chunks, negative coordinates, four LODs and both strides; duplicate records retained");
    }
}

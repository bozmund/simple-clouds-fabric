import dev.nonamecrackers2.simpleclouds.client.gui.CloudPreviewExport;
class CloudPreviewReadbackTest {
    public static void main(String[] args) {
        var bytes=java.nio.ByteBuffer.wrap(new byte[]{
            (byte)255,0,0,0, 0,(byte)255,0,64,
            0,0,(byte)255,(byte)255, 12,34,56,(byte)128});
        int[] pixels=CloudPreviewExport.copyRgba(bytes,2,2);
        if(!java.util.Arrays.equals(pixels,new int[]{0xff0000ff,0x800c2238,0x00ff0000,0x4000ff00}))
            throw new AssertionError("RGBA/ARGB, vertical flip or coverage alpha changed");
        System.out.println("PASS: byte-order independent RGBA readback, alpha preservation and vertical flip");
    }
}

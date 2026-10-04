import dev.nonamecrackers2.simpleclouds.client.renderer.v2.IndexedCloudBlend;
public final class IndexedCloudBlendSelectionTest {
    public static void main(String[] args) {
        for(boolean capable:new boolean[]{false,true})for(boolean developer:new boolean[]{false,true})
            for(String override:new String[]{null,"0","1","invalid"}) {
                boolean expected=capable && developer && "1".equals(override);
                if(IndexedCloudBlend.select(capable,developer,override)!=expected)
                    throw new AssertionError("Indexed capability/developer override selection");
            }
        System.out.println("PASS indexed backend selection: two-pass by default, indexed only with explicit developer opt-in on capable backends");
    }
}

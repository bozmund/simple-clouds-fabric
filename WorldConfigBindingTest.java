import dev.nonamecrackers2.simpleclouds.common.config.WorldConfigBinding;
import net.minecraftforge.common.ForgeConfigSpec;
import java.nio.file.*;

public class WorldConfigBindingTest {
    public static void main(String[] args) throws Exception {
        var root=Files.createTempDirectory(Path.of("build"),"world-config-binding-");
        var template=root.resolve("template.toml");
        var a=root.resolve("A/serverconfig/test.toml");
        var b=root.resolve("B/serverconfig/test.toml");
        var bad=root.resolve("bad.toml");
        Files.writeString(template,"value = 1\n");
        var builder=new ForgeConfigSpec.Builder();
        var value=builder.defineInRange("value",1,1,100); var spec=builder.build();
        try(var binding=new WorldConfigBinding(spec)) {
            binding.open(a,template); require(value.get()==1,"template seed");
            value.set(7); value.save(); byte[] savedA=Files.readAllBytes(a);
            binding.close(); require(!spec.isLoaded() && binding.path()==null,"unload ownership");
            binding.open(b,template); require(value.get()==1,"A leaked into B cache");
            value.set(9); value.save();
            binding.open(a,template); require(value.get()==7,"existing A overwritten/cache stale");
            require(java.util.Arrays.equals(savedA,Files.readAllBytes(a)),"world A file changed");
            Files.writeString(bad,"value = [\n"); byte[] badBefore=Files.readAllBytes(bad);
            try { binding.open(bad,template); throw new AssertionError("Malformed config accepted"); }
            catch(RuntimeException expected) {}
            require(value.get()==7 && binding.path().equals(a.toAbsolutePath()),"failed load replaced live A");
            require(java.util.Arrays.equals(badBefore,Files.readAllBytes(bad)),"bad input silently erased");
            require(Files.readString(template).equals("value = 1\n"),"legacy/template changed");
            binding.close(); binding.open(b,null); require(value.get()==9,"B did not persist independently");
            Files.writeString(b,"value = 12\n");
            require(binding.reloadIfChanged() && value.get()==12,"valid live edit/cache reset");
            require(!binding.reloadIfChanged(),"unchanged file reloaded");
            Files.writeString(b,"value = [\n"); byte[] invalid=Files.readAllBytes(b);
            try { binding.reloadIfChanged(); throw new AssertionError("malformed live edit accepted"); }
            catch(RuntimeException expected) {}
            require(value.get()==12 && spec.isLoaded(),"malformed live edit lost valid binding");
            require(java.util.Arrays.equals(invalid,Files.readAllBytes(b)),"malformed live edit overwritten");
            require(!binding.reloadIfChanged(),"bad revision retried without an edit");
            Files.writeString(b,"value = 9999\n"); invalid=Files.readAllBytes(b);
            try { binding.reloadIfChanged(); throw new AssertionError("out-of-range live edit accepted"); }
            catch(IllegalArgumentException expected) {}
            require(value.get()==12 && java.util.Arrays.equals(invalid,Files.readAllBytes(b)),"invalid value corrected/overwritten during live reload");
            Files.writeString(b,"value = 23\n");
            require(binding.reloadIfChanged() && value.get()==23,"valid edit after rejection did not recover");
            var replacement=b.resolveSibling("replacement.toml");
            Files.writeString(replacement,"value = 31\n");
            Files.move(replacement,b,StandardCopyOption.REPLACE_EXISTING);
            require(binding.reloadIfChanged() && value.get()==31,"atomic external replacement not detected");
            Files.delete(b);
            try { binding.reloadIfChanged(); throw new AssertionError("missing config accepted"); }
            catch(java.io.IOException expected) {}
            require(value.get()==31 && !Files.exists(b),"missing file recreated/lost live values");
            Files.writeString(b,"value = 44\n");
            require(binding.reloadIfChanged() && value.get()==44,"recreated file did not recover");
        } finally {
            for(var p : new Path[]{a,b,bad,template,a.getParent(),b.getParent(),a.getParent().getParent(),b.getParent().getParent(),root})
                Files.deleteIfExists(p);
        }
        System.out.println("PASS independent A/B binding, reopen/cache reset, seed preservation, malformed-load isolation; actual server lifecycle separate");
    }
    static void require(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
}

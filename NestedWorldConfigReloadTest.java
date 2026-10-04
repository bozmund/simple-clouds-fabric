import java.nio.file.*;
import java.util.Arrays;
import dev.nonamecrackers2.simpleclouds.common.config.WorldConfigBinding;
import net.minecraftforge.common.ForgeConfigSpec;

public class NestedWorldConfigReloadTest {
    public static void main(String[] args) throws Exception {
        var root=Files.createTempDirectory(Path.of("build"),"nested-config-reload-");
        var path=root.resolve("test.toml");
        var builder=new ForgeConfigSpec.Builder();
        builder.push("nested"); var value=builder.defineInRange("value",2,1,100); builder.pop();
        var spec=builder.build();
        try(var binding=new WorldConfigBinding(spec)) {
            binding.open(path,null); value.set(3); value.save();
            Files.writeString(path,"[nested]\nvalue = 9999\n");
            byte[] invalid=Files.readAllBytes(path);
            try { binding.reloadIfChanged(); throw new AssertionError("invalid nested value accepted"); }
            catch(IllegalArgumentException expected) {}
            require(value.get()==3,"validation copy mutated active nested value");
            require(Arrays.equals(invalid,Files.readAllBytes(path)),"invalid nested edit overwritten");
            Files.writeString(path,"[nested]\nvalue = 4\n");
            byte[] valid=Files.readAllBytes(path);
            require(binding.reloadIfChanged() && value.get()==4,"comment-free nested edit rejected");
            require(Arrays.equals(valid,Files.readAllBytes(path)),"valid live edit rewritten to add comments");
        } finally { Files.deleteIfExists(path); Files.deleteIfExists(root); }
        System.out.println("PASS: nested validation isolation; comment-free valid reload preserves exact disk bytes");
    }
    private static void require(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
}

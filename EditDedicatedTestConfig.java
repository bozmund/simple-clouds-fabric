import java.nio.file.Path;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.io.WritingMode;

/** Exact disposable test-world config only; cannot target a play-world file. */
public class EditDedicatedTestConfig {
    public static void main(String[] args) {
        if(args.length!=1 || !(args[0].equals("true") || args[0].equals("false")))
            throw new IllegalArgumentException("Expected true or false");
        Path path=Path.of("build/dedicated-boundary-smoke/CodexDedicatedServer20261001/serverconfig/simpleclouds-server.toml");
        try(var file=CommentedFileConfig.builder(path).sync().writingMode(WritingMode.REPLACE_ATOMIC).build()) {
            file.load(); file.set("whitelistAsBlacklist",Boolean.parseBoolean(args[0])); file.save();
        }
        System.out.println("Updated disposable dedicated world blacklist="+args[0]);
    }
}

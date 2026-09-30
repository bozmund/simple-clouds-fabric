import com.mojang.brigadier.CommandDispatcher;
import dev.nonamecrackers2.simpleclouds.common.command.CloudCommands;
import dev.nonamecrackers2.simpleclouds.common.command.CloudCommandSource;
import dev.nonamecrackers2.simpleclouds.common.cloud.CloudTypeDataManager;
import net.minecraft.commands.CommandSourceStack;

public class CloudCommandTreeTest {
    public static void main(String[] args) {
        dev.nonamecrackers2.simpleclouds.common.api.SimpleCloudsAPIImpl.bootstrap();
        var dispatcher=new CommandDispatcher<CommandSourceStack>();
        CloudCommands.register(dispatcher,"clouds",source -> false,CloudCommandSource.SERVER,CloudTypeDataManager.getServerInstance());
        var root=dispatcher.getRoot().getChild("simpleclouds");
        if(root==null || root.getChild("clouds")==null) throw new AssertionError("server tree missing");
        var clouds=root.getChild("clouds");
        if(clouds.getRequirement().test(null)) throw new AssertionError("permission predicate lost");
        if(clouds.getChild("clear")==null || clouds.getChild("get")==null)
            throw new AssertionError("server branches lost");
        System.out.println("PASS: server-typed cloud command tree builds and preserves caller permissions");
    }
}

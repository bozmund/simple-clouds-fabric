import java.nio.file.Path;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;

/** Read-only inspection of saved singleplayer effects; never writes a world. */
public class InspectPlayerEffects {
    public static void main(String[] args) throws Exception {
        var root = NbtIo.readCompressed(Path.of(args[0]), NbtAccounter.unlimitedHeap());
        var data = root.getCompoundOrEmpty("Data");
        var player = root.contains("Data") ? data.getCompoundOrEmpty("Player") : root;
        for (String key : new String[] {"Pos", "Dimension", "active_effects", "ActiveEffects", "playerGameType"})
            System.out.println(key + "=" + player.get(key));
        System.out.println("LevelName=" + data.get("LevelName"));
    }
}

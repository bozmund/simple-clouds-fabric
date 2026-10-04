import dev.nonamecrackers2.simpleclouds.common.command.ServerConfigCommands;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.nio.file.*;

/** Parsing/source checks only; live dispatcher, permissions and saves are separate. */
public class ServerConfigCommandsTest {
    enum Example { FIRST, SECOND }
    static int checks;
    static void equal(Object def,String text,Object expected) throws Exception {
        if(!ServerConfigCommands.parseValue(def,text).equals(expected)) throw new AssertionError(text);
        checks++;
    }
    static void rejects(Object def,String text) throws Exception {
        try { ServerConfigCommands.parseValue(def,text); throw new AssertionError("Accepted: "+text); }
        catch(CommandSyntaxException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        equal(0,"12",12); equal(0,"-1",-1); equal(0.0,"1.25",1.25);
        equal(false,"true",true); equal(true,"false",false);
        equal("","text with spaces","text with spaces");
        equal(Example.FIRST,"second",Example.SECOND);
        for(String text : new String[]{"1.5","12 junk","999999999999999"}) rejects(0,text);
        for(String text : new String[]{"TRUE","yes","false extra"}) rejects(false,text);
        for(String text : new String[]{"NaN","Infinity","1.0 junk"}) rejects(0.0,text);
        rejects(Example.FIRST,"unknown"); rejects(0L,"1"); rejects(java.util.List.of(),"x");
        String src=Files.readString(Path.of("src/main/java/dev/nonamecrackers2/simpleclouds/common/command/ServerConfigCommands.java"));
        if(src.contains("net.minecraft.client") || src.contains("FabricClientCommandSource")
                || !src.contains("requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))")
                || !src.contains("config.save()") || !src.contains("config.set(old)")
                || !src.contains("valueSpec.test(value)")) throw new AssertionError("Server isolation/permission/save validation contract");
        String loader=Files.readString(Path.of("src/main/java/dev/nonamecrackers2/simpleclouds/common/config/SimpleCloudsConfigLoader.java"));
        if(!loader.contains("WritingMode.REPLACE_ATOMIC")) throw new AssertionError("Non-atomic config save");
        failedSave();
        System.out.println("PASS "+checks+" server config parsing/save-failure checks; live/multiplayer tests separate");
    }
    static void failedSave() throws Exception {
        var directory=Files.createTempDirectory(Path.of("build"),"config-save-failure-");
        var path=directory.resolve("probe.toml");
        var permissions=Files.getPosixFilePermissions(directory);
        try(var file=com.electronwill.nightconfig.core.file.CommentedFileConfig.builder(path)
                .sync().writingMode(com.electronwill.nightconfig.core.io.WritingMode.REPLACE_ATOMIC)
                .onFileNotFound(com.electronwill.nightconfig.core.file.FileNotFoundAction.CREATE_EMPTY).build()) {
            file.load();
            var builder=new net.minecraftforge.common.ForgeConfigSpec.Builder();
            var value=builder.define("probe",1);
            var spec=builder.build(); spec.setConfig(file);
            byte[] before=Files.readAllBytes(path);
            var method=ServerConfigCommands.class.getDeclaredMethod("set",net.minecraft.commands.CommandSourceStack.class,
                    net.minecraftforge.common.ForgeConfigSpec.class,net.minecraftforge.fml.config.ModConfig.Type.class,
                    net.minecraftforge.common.ForgeConfigSpec.ConfigValue.class,Object.class,boolean.class);
            method.setAccessible(true);
            Files.setPosixFilePermissions(directory,java.util.Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
            try { method.invoke(null,null,spec,net.minecraftforge.fml.config.ModConfig.Type.COMMON,value,2,false);
                throw new AssertionError("Write unexpectedly succeeded; failure fixture invalid"); }
            catch(java.lang.reflect.InvocationTargetException error) {
                if(!(error.getCause() instanceof CommandSyntaxException)) throw error;
            }
            if(value.get()!=1 || !java.util.Arrays.equals(before,Files.readAllBytes(path)))
                throw new AssertionError("Failed save damaged memory or original file");
            checks++;
        } finally {
            Files.setPosixFilePermissions(directory,permissions);
            Files.deleteIfExists(path); Files.delete(directory);
        }
    }
}

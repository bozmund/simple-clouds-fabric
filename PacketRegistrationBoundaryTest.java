import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Checks the compiled common networking boundary and all receiver/codec pairs. */
public class PacketRegistrationBoundaryTest {
    public static void main(String[] args) throws Exception {
        String commonRoot = "src/main/java/dev/nonamecrackers2/simpleclouds/";
        String common = Files.readString(Path.of(commonRoot + "common/packet/SimpleCloudsPacketHandlers.java"));
        String client = Files.readString(Path.of(commonRoot + "client/packet/SimpleCloudsClientPacketRegistrations.java"));
        String[] packets = {"SendServerConfig", "UpdateCloudManager", "SendCloudManager", "SendCloudRegions",
                "SendCloudTypes", "SpawnLightning", "NotifyCloudModeUpdated", "NotifySingleModeCloudTypeUpdated"};
        for (String packet : packets) {
            require(common.contains("registerClientboundType(" + packet + "Packet.TYPE, " + packet + "Packet.CODEC)"), packet + " codec missing");
            require(client.contains("registerGlobalReceiver(" + packet + "Packet.TYPE,"), packet + " receiver missing");
        }
        for (String className : new String[] {
                "dev/nonamecrackers2/simpleclouds/common/packet/SimpleCloudsPacketHandlers",
                "dev/nonamecrackers2/simpleclouds/common/command/CloudCommandSource",
                "nonamecrackers2/crackerslib/common/packet/PacketUtil"}) {
            // Class-file constant-pool ASCII names remain directly searchable. Do not
            // load a client class accidentally while testing the common boundary.
            String bytecode = new String(Files.readAllBytes(Path.of("build/classes/java/main/" + className + ".class")), StandardCharsets.ISO_8859_1);
            require(!bytecode.contains("net/fabricmc/fabric/api/client/"), className + " links Fabric client API");
            require(!bytecode.contains("net/minecraft/client/"), className + " links Minecraft client code");
            require(!bytecode.contains("simpleclouds/client/"), className + " links mod client code");
        }
        require(client.split("registerGlobalReceiver\\(", -1).length - 1 == packets.length, "unexpected receiver count");
        require(common.split("registerClientboundType\\(", -1).length - 1 == packets.length, "unexpected codec count");
        System.out.println("PASS: eight codec/receiver pairs; compiled common networking has no client API links (not a dedicated-server startup test)");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

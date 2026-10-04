import java.util.ArrayList;
import java.util.List;
import dev.nonamecrackers2.simpleclouds.api.common.cloud.CloudMode;
import dev.nonamecrackers2.simpleclouds.client.config.ClientServerConfig;
import dev.nonamecrackers2.simpleclouds.common.config.ServerConfigSnapshot;
import dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.SendServerConfigPacket;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;

public class ServerConfigSyncTest {
    static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        var dimensions = new ArrayList<>(List.of("minecraft:overworld", "test:cold"));
        var original = new ServerConfigSnapshot(CloudMode.SINGLE, "simpleclouds:itty_bitty", dimensions, false);
        dimensions.clear();
        require(original.dimensionWhitelist().size() == 2, "mutable dimension input leaked");
        try { original.dimensionWhitelist().clear(); throw new AssertionError("mutable dimension result"); }
        catch (UnsupportedOperationException expected) {}
        for (CloudMode mode : CloudMode.values()) for (boolean blacklist : new boolean[] {false, true}) {
            var snapshot = new ServerConfigSnapshot(mode, "test:cloud", original.dimensionWhitelist(), blacklist);
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                SendServerConfigPacket.CODEC.encode(buffer, new SendServerConfigPacket(snapshot));
                var decoded = SendServerConfigPacket.CODEC.decode(buffer).config();
                require(snapshot.equals(decoded), "wire roundtrip changed configuration");
                require(buffer.readableBytes() == 0, "wire bytes not consumed");
                require(decoded.allowsDimension("test:cold") == !blacklist, "listed dimension policy");
                require(decoded.allowsDimension("test:other") == blacklist, "unlisted dimension policy");
            } finally { buffer.release(); }
        }
        for (int count : new int[] {-1, SendServerConfigPacket.MAX_DIMENSIONS + 1}) {
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                buffer.writeEnum(CloudMode.DEFAULT); buffer.writeUtf("test:cloud"); buffer.writeVarInt(count);
                try { SendServerConfigPacket.CODEC.decode(buffer); throw new AssertionError("invalid wire count accepted"); }
                catch (IllegalArgumentException expected) {}
            } finally { buffer.release(); }
        }
        ClientServerConfig.clear();
        require(ClientServerConfig.get() == null, "connection initial state");
        ClientServerConfig.updateCloudMode(CloudMode.AMBIENT);
        require(ClientServerConfig.get() == null, "partial packet fabricated full config");
        ClientServerConfig.receive(original);
        ClientServerConfig.updateCloudMode(CloudMode.AMBIENT);
        ClientServerConfig.updateSingleModeCloudType("test:changed");
        require(ClientServerConfig.get().cloudMode() == CloudMode.AMBIENT, "mode update lost");
        require(ClientServerConfig.get().singleModeCloudType().equals("test:changed"), "type update lost");
        require(ClientServerConfig.get().dimensionWhitelist().equals(original.dimensionWhitelist()), "partial update lost dimensions");
        ClientServerConfig.clear();
        require(ClientServerConfig.get() == null, "previous connection leaked");
        var next = new ServerConfigSnapshot(CloudMode.DEFAULT, "test:next", List.of(), true);
        ClientServerConfig.receive(next);
        require(ClientServerConfig.get().equals(next), "new connection retained old values");

        // A live integrated server owns SERVER_SPEC. Receiving network config must
        // not rebind it or edit its values, including legacy update notifications.
        var memory = com.electronwill.nightconfig.core.CommentedConfig.inMemory();
        SimpleCloudsConfig.SERVER_SPEC.setConfig(memory);
        SimpleCloudsConfig.SERVER_SPEC.afterReload();
        var authoritative = ServerConfigSnapshot.capture();
        ClientServerConfig.receive(original);
        ClientServerConfig.updateCloudMode(CloudMode.AMBIENT);
        ClientServerConfig.updateSingleModeCloudType("test:remote");
        require(ServerConfigSnapshot.capture().equals(authoritative), "client mutated integrated server config");
        ClientServerConfig.clear();
        SimpleCloudsConfig.SERVER_SPEC.setConfig(null);
        SimpleCloudsConfig.SERVER_SPEC.afterReload();
        try { ServerConfigSnapshot.capture(); throw new AssertionError("unloaded server capture accepted"); }
        catch (IllegalStateException expected) {}
        System.out.println("PASS: config wire roundtrips, allocation bounds, immutable dimensions, whitelist/blacklist, connection reset and integrated-server ownership");
    }
}

import dev.nonamecrackers2.simpleclouds.client.cloud.ClientSideCloudTypeManager;
import dev.nonamecrackers2.simpleclouds.common.cloud.CloudType;
import java.util.Map;

class CloudTypeConnectionLifetimeTest {
    public static void main(String[] args) {
        var manager = ClientSideCloudTypeManager.getInstance();
        manager.clearSynced();
        if (manager.hasReceivedSynced()) throw new AssertionError("new connection must be unbound");
        manager.receiveSynced(Map.of(), new CloudType[0]);
        if (!manager.hasReceivedSynced() || !manager.getCloudTypes().isEmpty()
                || manager.getIndexedCloudTypes().length != 0)
            throw new AssertionError("empty server snapshot must remain authoritative");
        manager.clearSynced();
        if (manager.hasReceivedSynced()) throw new AssertionError("disconnect retained server authority");
        System.out.println("PASS: connection clear and explicit empty server snapshot authority");
    }
}

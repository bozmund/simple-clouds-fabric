import dev.nonamecrackers2.simpleclouds.common.cloud.CloudTypeDataManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.InactiveProfiler;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

class CloudTypeResourcePathTest {
    static int opened, closed;
    static final class Probe extends CloudTypeDataManager {
        Map<Identifier, com.google.gson.JsonElement> prepareFiles(ResourceManager resources) {
            return prepare(resources, InactiveProfiler.INSTANCE);
        }
    }
    static final class SpawnProbe extends dev.nonamecrackers2.simpleclouds.common.cloud.spawning.CloudSpawningDataManager {
        SpawnProbe() { super(new CloudTypeDataManager()); }
        Map<Identifier, com.google.gson.JsonElement> prepareFiles(ResourceManager resources) {
            return prepare(resources, InactiveProfiler.INSTANCE);
        }
    }
    static Resource resource(String json) {
        var pack = (PackResources)Proxy.newProxyInstance(PackResources.class.getClassLoader(),
                new Class<?>[]{PackResources.class}, (p, m, a) -> null);
        return new Resource(pack, () -> {
            opened++;
            return new ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                @Override public void close() throws java.io.IOException { closed++; super.close(); }
            };
        });
    }
    public static void main(String[] args) {
        var files = new LinkedHashMap<Identifier, Resource>();
        files.put(Identifier.parse("test:cloud_types/storm.json"), resource("{}"));
        files.put(Identifier.parse("test:cloud_types/mountain/storm.json"), resource("{}"));
        files.put(Identifier.parse("test:cloud_types/coast/storm.json"), resource("{}"));
        files.put(Identifier.parse("test:cloud_types/invalid.json"), resource("{bad-json"));
        var resources = (ResourceManager)Proxy.newProxyInstance(ResourceManager.class.getClassLoader(),
                new Class<?>[]{ResourceManager.class}, (p, m, a) -> {
                    if (m.getName().equals("listResources")) return files;
                    throw new UnsupportedOperationException(m.getName());
                });
        var result = new Probe().prepareFiles(resources);
        if (!result.keySet().equals(java.util.Set.of(Identifier.parse("test:storm"),
                Identifier.parse("test:mountain/storm"), Identifier.parse("test:coast/storm"))))
            throw new AssertionError("Datapack IDs flattened or malformed JSON accepted: " + result.keySet());
        if (opened != 4 || closed != 4) throw new AssertionError("Resource reader leak: " + opened + "/" + closed);
        files.clear();
        files.put(Identifier.parse("simpleclouds:cloud_spawning/config.json"), resource("{}"));
        files.put(Identifier.parse("test:cloud_spawning/mountain/config.json"), resource("{}"));
        var spawnResult = new SpawnProbe().prepareFiles(resources);
        if (!spawnResult.keySet().equals(java.util.Set.of(Identifier.parse("simpleclouds:config"),
                Identifier.parse("test:mountain/config")))) throw new AssertionError("Spawning IDs flattened");
        if (opened != 6 || closed != 6) throw new AssertionError("Spawning reader leak");
        System.out.println("PASS: original nested type/spawning IDs and readers closed on valid/invalid JSON");
    }
}

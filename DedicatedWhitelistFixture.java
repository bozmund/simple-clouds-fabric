import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Generated public test identities only; no account credentials. */
class DedicatedWhitelistFixture {
    public static void main(String[] args) throws Exception {
        Path root = Path.of("/home/jan/simple-clouds-fabric/build/dedicated-boundary-smoke");
        Path target = root.resolve("whitelist.json");
        if (Files.isSymbolicLink(root) || Files.isSymbolicLink(target)) throw new IllegalStateException("unsafe fixture path");
        String properties = Files.readString(root.resolve("server.properties"));
        if (!properties.contains("server-ip=127.0.0.1\n") || !properties.contains("online-mode=false\n"))
            throw new IllegalStateException("not the isolated offline test server");
        StringBuilder json = new StringBuilder("[\n");
        for (String name : new String[]{"CodexA", "CodexB"}) {
            UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            if (json.length() > 2) json.append(",\n");
            json.append("{\"uuid\":\"").append(id).append("\",\"name\":\"").append(name).append("\"}");
        }
        Files.writeString(target, json.append("\n]\n").toString());
        System.out.println("Prepared exact offline UUIDs for two localhost test identities");
    }
}

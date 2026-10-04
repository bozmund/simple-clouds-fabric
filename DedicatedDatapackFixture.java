import java.nio.file.*;

/** Two same-basename cloud resources exercise nested IDs in an actual /reload. */
class DedicatedDatapackFixture {
    static void put(Path file, byte[] bytes) throws Exception {
        Files.createDirectories(file.getParent());
        if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)) {
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)
                    || !java.util.Arrays.equals(bytes,Files.readAllBytes(file)))
                throw new IllegalStateException("Unexpected existing test fixture: "+file);
        } else Files.write(file,bytes,StandardOpenOption.CREATE_NEW);
    }
    public static void main(String[] args) throws Exception {
        Path project=Path.of("/home/jan/simple-clouds-fabric");
        if(!Path.of("").toAbsolutePath().normalize().equals(project)) throw new IllegalStateException("Wrong project");
        Path server=project.resolve("build/dedicated-boundary-smoke");
        String properties=Files.readString(server.resolve("server.properties"));
        if(!properties.contains("server-ip=127.0.0.1") || !properties.contains("server-port=25575")
                || !properties.contains("level-name=CodexDedicatedServer20261001"))
            throw new IllegalStateException("Not the accepted scratch server");
        Path pack=server.resolve("CodexDedicatedServer20261001/datapacks/codex-cloud-types");
        for(Path part=pack;part!=null;part=part.getParent())
            if(Files.isSymbolicLink(part)) throw new IllegalStateException("Symlink in fixture path");
        put(pack.resolve("pack.mcmeta"),"{\"pack\":{\"description\":\"Codex disposable nested cloud reload fixture\",\"min_format\":121,\"max_format\":121}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] source=Files.readAllBytes(project.resolve("src/main/resources/data/simpleclouds/cloud_types/cumulus.json"));
        put(pack.resolve("data/codex/cloud_types/storm.json"),source);
        put(pack.resolve("data/codex/cloud_types/nested/storm.json"),source);
        System.out.println("Prepared exact nested-ID scratch datapack; metadata matches pinned MC 26.3 data 121.0");
    }
}

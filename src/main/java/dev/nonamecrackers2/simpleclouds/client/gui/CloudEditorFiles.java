package dev.nonamecrackers2.simpleclouds.client.gui;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import dev.nonamecrackers2.simpleclouds.common.cloud.CloudType;
import net.minecraft.resources.Identifier;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Original cloud-type JSON format, confined to the user's editor export folder. */
public final class CloudEditorFiles {
    private final Path directory;
    public CloudEditorFiles(Path gameDirectory) {
        this.directory=gameDirectory.toAbsolutePath().normalize().resolve("simpleclouds/cloudtypes");
    }
    public Path file(String name) {
        String base=name.endsWith(".json")?name.substring(0,name.length()-5):name;
        if(!base.matches("[a-z0-9][a-z0-9_.-]{0,95}"))
            throw new IllegalArgumentException("Use a lowercase name with letters, numbers, '.', '_' or '-'");
        return this.directory.resolve(base+".json");
    }
    private void checkDirectory() throws IOException {
        for(Path part=this.directory; part!=null; part=part.getParent())
            if(Files.isSymbolicLink(part)) throw new IOException("Editor path contains a symbolic link: "+part);
        Files.createDirectories(this.directory);
    }
    public Path save(String name, CloudType type, boolean overwriteConfirmed) throws IOException {
        checkDirectory();
        Path file=file(name);
        String json=new GsonBuilder().setPrettyPrinting().create().toJson(type.toJson());
        var mode=overwriteConfirmed?StandardOpenOption.CREATE:StandardOpenOption.CREATE_NEW;
        var options=new java.util.HashSet<java.nio.file.OpenOption>();
        options.add(StandardOpenOption.WRITE); options.add(mode); options.add(LinkOption.NOFOLLOW_LINKS);
        if(overwriteConfirmed) options.add(StandardOpenOption.TRUNCATE_EXISTING);
        try(var channel=Files.newByteChannel(file, options)) {
            var bytes=java.nio.ByteBuffer.wrap(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            while(bytes.hasRemaining()) channel.write(bytes);
        }
        return file;
    }
    public CloudType load(String name, Identifier id) throws IOException {
        checkDirectory();
        Path file=file(name);
        if(!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file)>1024*1024)
            throw new IOException("Not a regular cloud JSON file, or exceeds 1 MiB: "+file);
        try(var stream=Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
            var reader=new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8)) {
            return CloudType.readFromJson(id, JsonParser.parseReader(reader).getAsJsonObject());
        }
    }
}

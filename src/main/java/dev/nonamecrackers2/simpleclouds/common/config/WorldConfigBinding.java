package dev.nonamecrackers2.simpleclouds.common.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import com.electronwill.nightconfig.core.io.WritingMode;
import com.electronwill.nightconfig.toml.TomlFormat;
import net.minecraftforge.common.ForgeConfigSpec;

/** Owns one live world binding; existing world files always win over templates. */
public final class WorldConfigBinding implements AutoCloseable {
    private final ForgeConfigSpec spec;
    private CommentedFileConfig file;
    private Path path;
    private FileStamp observedStamp;
    private record FileStamp(java.nio.file.attribute.FileTime modified,long size,Object key) {}
    private static FileStamp stamp(Path path) throws IOException {
        var attrs=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class);
        return new FileStamp(attrs.lastModifiedTime(),attrs.size(),attrs.fileKey());
    }
    public WorldConfigBinding(ForgeConfigSpec spec) { this.spec=spec; }
    public synchronized Path path() { return this.path; }
    public synchronized void open(Path target,Path template) throws IOException {
        target=target.toAbsolutePath().normalize();
        if(target.equals(path) && spec.isLoaded()) return;
        Files.createDirectories(target.getParent());
        if(!Files.exists(target) && template!=null && Files.isRegularFile(template))
            Files.copy(template,target); // no REPLACE_EXISTING: never erase world settings
        var candidate=CommentedFileConfig.builder(target,TomlFormat.instance()).sync()
                .writingMode(WritingMode.REPLACE_ATOMIC).onFileNotFound(FileNotFoundAction.CREATE_EMPTY).build();
        try {
            candidate.load(); // malformed input fails before replacing the live binding
            spec.setConfig(candidate);
            spec.afterReload();
        } catch(RuntimeException failure) {
            candidate.close();
            if(file!=null) { spec.setConfig(file); spec.afterReload(); }
            else { spec.setConfig(null); spec.afterReload(); }
            throw failure;
        }
        if(file!=null) file.close();
        file=candidate; path=target;
        observedStamp=stamp(target);
    }

    /** Called on the server tick thread. A bad edit never replaces valid live data. */
    public synchronized boolean reloadIfChanged() throws IOException {
        if(file==null || path==null) return false;
        FileStamp incoming=stamp(path);
        if(incoming.equals(observedStamp)) return false;
        // Do not parse/log the same bad revision every tick. A later edit retries.
        observedStamp=incoming;
        var candidate=CommentedFileConfig.builder(path,TomlFormat.instance()).sync()
                .writingMode(WritingMode.REPLACE_ATOMIC).onFileNotFound(FileNotFoundAction.THROW_ERROR).build();
        try {
            candidate.load();
            // setConfig normally corrects and writes invalid values to disk. A
            // live reload must not overwrite a bad edit or discard valid values.
            var validation=copyValues(candidate);
            spec.correct(validation);
            // Forge's isCorrect also requires its generated comments. Missing
            // comments are harmless; compare only actual values against a
            // corrected in-memory copy, without saving that copy.
            if(!values(candidate).equals(values(validation)))
                throw new IllegalArgumentException("Invalid server configuration; retaining live values: "+path);
            if(!incoming.equals(stamp(path)))
                throw new IOException("Server configuration changed while being read: "+path);
            candidate.putAllComments(validation);
            spec.setConfig(candidate);
            spec.afterReload();
        } catch(RuntimeException | IOException failure) {
            candidate.close();
            // Validation/parse failures happen before binding. Restore only if a
            // failure happened during the binding itself.
            spec.setConfig(file); spec.afterReload();
            if(failure instanceof IOException) observedStamp=null;
            throw failure;
        }
        file.close(); file=candidate;
        return true;
    }
    private static Object values(com.electronwill.nightconfig.core.UnmodifiableConfig config) {
        var result=new java.util.LinkedHashMap<String,Object>();
        config.valueMap().forEach((key,value) -> result.put(key,
                value instanceof com.electronwill.nightconfig.core.UnmodifiableConfig nested ? values(nested) : value));
        return result;
    }
    private static com.electronwill.nightconfig.core.CommentedConfig copyValues(com.electronwill.nightconfig.core.UnmodifiableConfig config) {
        var result=com.electronwill.nightconfig.core.CommentedConfig.inMemory();
        config.valueMap().forEach((key,value) -> result.set(java.util.List.of(key),
                value instanceof com.electronwill.nightconfig.core.UnmodifiableConfig nested ? copyValues(nested)
                : value instanceof java.util.List<?> list ? new java.util.ArrayList<>(list) : value));
        return result;
    }
    @Override public synchronized void close() {
        spec.setConfig(null); spec.afterReload();
        if(file!=null) file.close();
        file=null; path=null; observedStamp=null;
    }
}

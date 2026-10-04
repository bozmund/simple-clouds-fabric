package dev.nonamecrackers2.simpleclouds.client.shader.compute;

import com.google.common.collect.ImmutableMap;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.nonamecrackers2.simpleclouds.client.shader.buffer.BindingManager;
import dev.nonamecrackers2.simpleclouds.client.shader.buffer.ShaderStorageBufferObject;
import dev.nonamecrackers2.simpleclouds.client.shader.buffer.UniqueBinding;
import dev.nonamecrackers2.simpleclouds.client.shader.buffer.WithBinding;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Original generator's compute contract, adapted to the 26.3 GL backend.
 * Raw GL operations restore the backend's program, texture and buffer bindings.
 * There is deliberately no CPU-generation fallback here. */
public final class ComputeShader implements AutoCloseable {
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*#moj_import\\s+(?:<([^>]+)>|\"([^\"]+)\")\\s*$");
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^}]+)}");
    private final String name;
    private int id;
    private final Map<String, WithBinding> buffers = new LinkedHashMap<>();
    private final Map<Integer, Sampler> samplers = new LinkedHashMap<>();
    // Program locations and context limits cannot change during this linked
    // program's lifetime. Reload creates a fresh instance, not a stale cache.
    private final Map<String, Integer> uniformLocations = new HashMap<>();
    private final int[] maxWorkGroupCounts = new int[3];

    private record Sampler(int target, int bindingQuery, int texture) {}
    private record BufferBinding(int index, int buffer, long start, long size) {
        void restore() {
            if (buffer != 0 && size > 0)
                GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, index, buffer, start, size);
            else GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, index, buffer);
        }
    }

    private ComputeShader(String name, int id) {
        this.name = name; this.id = id;
        for (int axis = 0; axis < 3; axis++)
            maxWorkGroupCounts[axis] = GL30.glGetIntegeri(GL43.GL_MAX_COMPUTE_WORK_GROUP_COUNT, axis);
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (id == -1) return;
        for (WithBinding buffer : buffers.values()) {
            int binding = buffer.getBinding();
            buffer.close();
            BindingManager.freeShaderStorageBinding(binding);
        }
        buffers.clear();
        samplers.clear();
        uniformLocations.clear();
        GL20.glDeleteProgram(id);
        id = -1;
    }

    private void assertValid() {
        RenderSystem.assertOnRenderThread();
        if (!isValid()) throw new IllegalStateException("Closed compute shader: " + name);
    }

    public void forUniform(String name, BiConsumer<Integer, Integer> consumer) {
        assertValid();
        int location = uniformLocation(name);
        if (location != -1) consumer.accept(id, location);
    }

    private int uniformLocation(String name) {
        return uniformLocations.computeIfAbsent(name, key -> GL20.glGetUniformLocation(id, key));
    }

    private void sampler(String name, int texture, int unit, int target, int bindingQuery) {
        assertValid();
        if (unit < 0 || unit >= GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS))
            throw new IllegalArgumentException("Invalid texture unit: " + unit);
        int location = uniformLocation(name);
        if (location == -1) throw new IllegalArgumentException("Unknown sampler: " + name);
        GL41.glProgramUniform1i(id, location, unit);
        samplers.put(unit, new Sampler(target, bindingQuery, texture));
    }
    public void setSampler2D(String name, int texture, int unit) {
        sampler(name, texture, unit, GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_BINDING_2D);
    }
    public void setSampler3D(String name, int texture, int unit) {
        sampler(name, texture, unit, GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_BINDING_3D);
    }
    public void setSampler2DArray(String name, int texture, int unit) {
        sampler(name, texture, unit, GL30.GL_TEXTURE_2D_ARRAY, GL30.GL_TEXTURE_BINDING_2D_ARRAY);
    }

    public int findAndUseSSBOBinding(String name) {
        assertValid();
        if (buffers.containsKey(name)) throw new IllegalArgumentException("Duplicate SSBO: " + name);
        int index = GL43.glGetProgramResourceIndex(id, GL43.GL_SHADER_STORAGE_BLOCK, name);
        if (index == GL43.GL_INVALID_INDEX) throw new IllegalArgumentException("Unknown SSBO: " + name);
        int binding = BindingManager.getAvailableShaderStorageBinding();
        GL43.glShaderStorageBlockBinding(id, index, binding);
        BindingManager.useShaderStorageBinding(binding);
        buffers.put(name, new UniqueBinding(binding));
        return binding;
    }
    public ShaderStorageBufferObject createAndBindSSBO(String name, int usage) {
        int binding = findAndUseSSBOBinding(name);
        ShaderStorageBufferObject buffer = new ShaderStorageBufferObject(GL45.glCreateBuffers(), binding, usage);
        buffers.put(name, buffer);
        return buffer;
    }
    private WithBinding buffer(String name) {
        assertValid();
        return Objects.requireNonNull(buffers.get(name), "Unknown SSBO: " + name);
    }
    public ShaderStorageBufferObject getShaderStorageBuffer(String name) {
        if (!(buffer(name) instanceof ShaderStorageBufferObject buffer))
            throw new IllegalArgumentException("External SSBO binding: " + name);
        return buffer;
    }
    public int getShaderStorageBinding(String name) { return buffer(name).getBinding(); }
    public void setImageUnit(String name, int unit) {
        assertValid();
        int location = uniformLocation(name);
        if (location == -1) throw new IllegalArgumentException("Unknown image: " + name);
        GL41.glProgramUniform1i(id, location, unit);
    }

    public void dispatch(int x, int y, int z, boolean wait) {
        assertValid();
        int[] counts = {x, y, z};
        for (int axis = 0; axis < 3; axis++)
            if (counts[axis] <= 0 || counts[axis] > maxWorkGroupCounts[axis])
                throw new IllegalArgumentException("Invalid compute group count on axis " + axis + ": " + counts[axis]);
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousBuffer = GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        List<BufferBinding> previousBindings = new ArrayList<>();
        Map<Integer, Integer> previousTextures = new LinkedHashMap<>();
        try {
            for (WithBinding entry : buffers.values()) {
                if (!(entry instanceof ShaderStorageBufferObject buffer)) continue;
                int binding = buffer.getBinding();
                previousBindings.add(new BufferBinding(binding,
                    GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, binding),
                    GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START, binding),
                    GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE, binding)));
                GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, buffer.getId());
            }
            for (var entry : samplers.entrySet()) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + entry.getKey());
                Sampler sampler = entry.getValue();
                previousTextures.put(entry.getKey(), GL11.glGetInteger(sampler.bindingQuery()));
                GL11.glBindTexture(sampler.target(), sampler.texture());
            }
            GL20.glUseProgram(id);
            GL43.glDispatchCompute(x, y, z);
            if (wait) GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT
                | GL42.GL_ATOMIC_COUNTER_BARRIER_BIT | GL42.GL_UNIFORM_BARRIER_BIT
                | GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        } finally {
            for (BufferBinding binding : previousBindings) binding.restore();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, previousBuffer);
            for (var entry : previousTextures.entrySet()) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + entry.getKey());
                GL11.glBindTexture(samplers.get(entry.getKey()).target(), entry.getValue());
            }
            GL13.glActiveTexture(previousActiveTexture);
            GL20.glUseProgram(previousProgram);
        }
    }
    public void dispatchAndWait(int x, int y, int z) { dispatch(x, y, z, true); }
    public String getName() { return name; }
    public int getId() { return id; }
    public boolean isValid() { return id != -1; }
    @Override public String toString() { return "ComputeShader[id=" + id + ", name=" + name + "]"; }

    public static ComputeShader loadShader(Identifier location, ResourceManager resources,
            int x, int y, int z) throws IOException {
        return loadShader(location, resources, x, y, z, ImmutableMap.of());
    }
    public static ComputeShader loadShader(Identifier location, ResourceManager resources,
            int x, int y, int z, ImmutableMap<String, String> parameters) throws IOException {
        RenderSystem.assertOnRenderThread();
        int[] local = {x, y, z};
        for (int axis = 0; axis < 3; axis++)
            if (local[axis] <= 0 || local[axis] > GL30.glGetIntegeri(GL43.GL_MAX_COMPUTE_WORK_GROUP_SIZE, axis))
                throw new IOException("Invalid compute local size on axis " + axis + ": " + local[axis]);
        if ((long)x * y * z > GL11.glGetInteger(GL43.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS))
            throw new IOException("Compute local invocation count exceeds device limit");
        Map<String, String> variables = new HashMap<>(parameters);
        variables.put("LOCAL_SIZE_X", Integer.toString(x));
        variables.put("LOCAL_SIZE_Y", Integer.toString(y));
        variables.put("LOCAL_SIZE_Z", Integer.toString(z));
        Identifier file = Identifier.fromNamespaceAndPath(location.getNamespace(), "shaders/compute/" + location.getPath() + ".comp");
        String source = expand(resources, file, variables, new HashSet<>());
        int shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        int program = -1;
        try {
            GL20.glShaderSource(shader, source);
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0)
                throw new IOException("Compute compile failed for " + file + ": " + GL20.glGetShaderInfoLog(shader, 32768));
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, shader);
            GL20.glLinkProgram(program);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0)
                throw new IOException("Compute link failed for " + file + ": " + GL20.glGetProgramInfoLog(program, 32768));
            GL20.glDetachShader(program, shader);
            return new ComputeShader(location.toString(), program);
        } catch (IOException | RuntimeException exception) {
            if (program != -1) GL20.glDeleteProgram(program);
            throw exception;
        } finally { GL20.glDeleteShader(shader); }
    }

    private static String expand(ResourceManager resources, Identifier file,
            Map<String, String> variables, Set<Identifier> imported) throws IOException {
        if (!imported.add(file)) return "";
        String source;
        try (var input = resources.getResourceOrThrow(file).open()) {
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher imports = IMPORT.matcher(source);
        StringBuilder expanded = new StringBuilder();
        while (imports.find()) {
            String requested = imports.group(1) != null ? imports.group(1) : imports.group(2);
            Identifier target;
            if (imports.group(1) != null) {
                Identifier name = Identifier.parse(requested);
                target = Identifier.fromNamespaceAndPath(name.getNamespace(), "shaders/include/" + name.getPath());
            } else {
                String path = file.getPath();
                target = Identifier.fromNamespaceAndPath(file.getNamespace(), path.substring(0, path.lastIndexOf('/') + 1) + requested);
            }
            String include = expand(resources, target, variables, imported).replaceAll("(?m)^\\s*#version[^\\r\\n]*", "");
            imports.appendReplacement(expanded, Matcher.quoteReplacement(include));
        }
        imports.appendTail(expanded);
        Matcher substitutions = VARIABLE.matcher(expanded);
        StringBuilder result = new StringBuilder();
        while (substitutions.find()) {
            String value = variables.get(substitutions.group(1));
            if (value == null) throw new IOException("Unknown compute variable " + substitutions.group() + " in " + file);
            substitutions.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        substitutions.appendTail(result);
        return result.toString();
    }
}

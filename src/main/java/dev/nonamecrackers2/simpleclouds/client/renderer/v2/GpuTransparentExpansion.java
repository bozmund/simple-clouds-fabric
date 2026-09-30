package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.backend.opengl.GlBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL45;

/** Expands six transparent draw faces per compact GPU cube without a CPU geometry readback.
 * This is an opt-in building block; the world renderer still uses its established CPU path. */
public final class GpuTransparentExpansion implements AutoCloseable {
    private static final int LOCAL_SIZE = 256;
    private static final int BINDINGS = 5;
    private static final int FACE_BYTES = CloudVertexFormat.BYTES_PER_INSTANCE_ALPHA;
    private final int maxCubes;
    private int program;
    private int expandedFaces;
    private int expandedIds;
    private int invalidCount;
    private int faceCount;

    public GpuTransparentExpansion(int maxCubes) {
        RenderSystem.assertOnRenderThread();
        if (!GpuCloudGeneration.isOpenGLBackend() || maxCubes <= 0 || maxCubes > 32 * 256 * 32)
            throw new IllegalArgumentException("Unsupported transparent cube capacity");
        this.maxCubes = maxCubes;
        try {
            if (GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS) < BINDINGS)
                throw new IllegalStateException("Not enough shader storage bindings");
            program = compile("assets/simpleclouds/shaders/compute/transparent_expand.comp");
            expandedFaces = GL45.glCreateBuffers();
            GL45.glNamedBufferData(expandedFaces,(long)maxCubes * 6 * FACE_BYTES,GL15.GL_DYNAMIC_DRAW);
            expandedIds = GL45.glCreateBuffers();
            GL45.glNamedBufferData(expandedIds,(long)maxCubes * 6 * Integer.BYTES,GL15.GL_DYNAMIC_DRAW);
            invalidCount = GL45.glCreateBuffers();
            GL45.glNamedBufferData(invalidCount,Integer.BYTES,GL15.GL_DYNAMIC_DRAW);
            checkGl("transparent expansion initialization");
        } catch (Exception failure) {
            close();
            throw new IllegalStateException("Could not initialize transparent expansion",failure);
        }
    }

    public int expandFrom(GpuCloudGeneration source,float worldBaseY) {
        RenderSystem.assertOnRenderThread();
        if (program == 0) throw new IllegalStateException("Transparent expansion is closed");
        int cubes = source.transparentCubeCount();
        int cells = source.completedCellCount();
        if (cubes < 0 || cubes > maxCubes || cells <= 0 || cells > 32 * 256 * 32
                || !Float.isFinite(worldBaseY))
            throw new IllegalArgumentException("Invalid transparent expansion dimensions");
        faceCount = 0;
        if (cubes == 0) return 0;
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int generic = GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        int[] bound = new int[BINDINGS];
        long[] offsets = new long[BINDINGS], sizes = new long[BINDINGS];
        for (int i = 0; i < BINDINGS; i++) {
            bound[i] = GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,i);
            offsets[i] = GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START,i);
            sizes[i] = GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE,i);
        }
        try {
            checkGl("before transparent expansion");
            GL45.glNamedBufferSubData(invalidCount,0,new int[]{0});
            GL20.glUseProgram(program);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,source.compactTransparentBufferHandle());
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,1,source.transparentCubeIdBufferHandle());
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,2,expandedFaces);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,3,expandedIds);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,4,invalidCount);
            GL30.glUniform1ui(GL20.glGetUniformLocation(program,"CubeCount"),cubes);
            GL30.glUniform1ui(GL20.glGetUniformLocation(program,"MaxCellId"),cells);
            GL20.glUniform1f(GL20.glGetUniformLocation(program,"WorldBaseY"),worldBaseY);
            GL43.glDispatchCompute((cubes + LOCAL_SIZE - 1) / LOCAL_SIZE,1,1);
            GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT | GL43.GL_SHADER_STORAGE_BARRIER_BIT);
            int[] invalid = new int[1];
            GL45.glGetNamedBufferSubData(invalidCount,0,invalid);
            checkGl("transparent expansion dispatch");
            if (invalid[0] != 0)
                throw new IllegalStateException("GPU emitted " + invalid[0] + " invalid transparent cube IDs");
            faceCount = cubes * 6;
            return faceCount;
        } finally {
            for (int i = 0; i < BINDINGS; i++) {
                if (bound[i] != 0 && sizes[i] > 0)
                    GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,i,bound[i],offsets[i],sizes[i]);
                else GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,i,bound[i]);
            }
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,generic);
            GL20.glUseProgram(previousProgram);
        }
    }

    public int faceCount() { return faceCount; }

    public void copyFacesTo(GpuBuffer target) { copyTo(target,expandedFaces,FACE_BYTES,"transparent faces"); }
    public void copyFaceIdsTo(GpuBuffer target) { copyTo(target,expandedIds,Integer.BYTES,"transparent face IDs"); }

    private void copyTo(GpuBuffer target,int source,int stride,String label) {
        RenderSystem.assertOnRenderThread();
        if (program == 0 || faceCount <= 0)
            throw new IllegalStateException("No completed " + label + " to copy");
        if (!(target instanceof GlBuffer gl) || target.isClosed()
                || (target.usage() & GpuBuffer.USAGE_COPY_DST) == 0
                || target.size() < (long)faceCount * stride)
            throw new IllegalArgumentException("Invalid " + label + " destination");
        GL45.glCopyNamedBufferSubData(source,gl.handle(),0,0,(long)faceCount * stride);
        checkGl(label + " copy");
    }

    /** Test-only readback; normal use copies directly between GPU buffers. */
    public ByteBuffer readFacesForTest() {
        RenderSystem.assertOnRenderThread();
        ByteBuffer result = ByteBuffer.allocateDirect(faceCount * FACE_BYTES).order(ByteOrder.nativeOrder());
        if (faceCount > 0) GL45.glGetNamedBufferSubData(expandedFaces,0,result);
        checkGl("transparent face test readback");
        return result;
    }

    /** Test-only readback of face-local IDs. */
    public int[] readFaceIdsForTest() {
        RenderSystem.assertOnRenderThread();
        int[] result = new int[faceCount];
        if (faceCount > 0) GL45.glGetNamedBufferSubData(expandedIds,0,result);
        checkGl("transparent face ID test readback");
        return result;
    }

    private static int compile(String path) throws Exception {
        String source;
        try (InputStream input = GpuTransparentExpansion.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new java.io.FileNotFoundException(path);
            source = new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
        int shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        try {
            GL20.glShaderSource(shader,source);
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS) == 0)
                throw new IllegalStateException(path + ": " + GL20.glGetShaderInfoLog(shader));
            int result = GL20.glCreateProgram();
            GL20.glAttachShader(result,shader);
            GL20.glLinkProgram(result);
            if (GL20.glGetProgrami(result,GL20.GL_LINK_STATUS) == 0) {
                String log = GL20.glGetProgramInfoLog(result);
                GL20.glDeleteProgram(result);
                throw new IllegalStateException(path + ": " + log);
            }
            return result;
        } finally {
            GL20.glDeleteShader(shader);
        }
    }

    private static void checkGl(String stage) {
        int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR)
            throw new IllegalStateException(stage + ": GL 0x" + Integer.toHexString(error));
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (program != 0) { GL20.glDeleteProgram(program); program = 0; }
        if (expandedFaces != 0) { GL15.glDeleteBuffers(expandedFaces); expandedFaces = 0; }
        if (expandedIds != 0) { GL15.glDeleteBuffers(expandedIds); expandedIds = 0; }
        if (invalidCount != 0) { GL15.glDeleteBuffers(invalidCount); invalidCount = 0; }
        faceCount = 0;
    }
}

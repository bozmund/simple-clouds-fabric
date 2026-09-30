package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import java.io.InputStream;
import java.nio.ByteBuffer;
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

/** GPU-side opaque face identity comparison and face-local scatter.
 * Only the three small counters need CPU readback; geometry stays on the GPU. */
public final class GpuFaceDelta implements AutoCloseable {
    private static final int LOCAL_SIZE = 256;
    private static final int BINDINGS = 10;
    private final int faceBytes;
    private final int maxIds;
    private final ByteBuffer zeroPresence;
    private final int[] presence = new int[2];
    private final int[] splitGeometry = new int[3];
    private int counters;
    private int markProgram;
    private int countProgram;
    private Counts lastCounts;

    public record Counts(int stable, int added, int removed) {}

    public GpuFaceDelta(int maxIds) { this(maxIds,GpuCloudGeneration.BYTES_PER_INSTANCE); }

    public GpuFaceDelta(int maxIds,int faceBytes) {
        RenderSystem.assertOnRenderThread();
        if (!GpuCloudGeneration.isOpenGLBackend() || maxIds <= 0
                || maxIds > 32 * 256 * 32 * 6)
            throw new IllegalArgumentException("Unsupported GPU face identity domain");
        this.maxIds = maxIds;
        if (faceBytes != GpuCloudGeneration.BYTES_PER_INSTANCE
                && faceBytes != CloudVertexFormat.BYTES_PER_INSTANCE_ALPHA)
            throw new IllegalArgumentException("Unsupported face instance format");
        this.faceBytes = faceBytes;
        this.zeroPresence = ByteBuffer.allocateDirect(((maxIds + 31) / 32) * Integer.BYTES);
        try {
            if (GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS) < BINDINGS)
                throw new IllegalStateException("Not enough shader storage bindings");
            markProgram = compile("assets/simpleclouds/shaders/compute/face_ids_mark.comp");
            countProgram = compile("assets/simpleclouds/shaders/compute/face_ids_delta_count.comp",faceBytes);
            for (int i = 0; i < presence.length; i++) {
                presence[i] = GL45.glCreateBuffers();
                GL45.glNamedBufferData(presence[i],zeroPresence.capacity(),GL15.GL_DYNAMIC_DRAW);
            }
            counters = GL45.glCreateBuffers();
            GL45.glNamedBufferData(counters,4L * Integer.BYTES,GL15.GL_DYNAMIC_DRAW);
            for (int i = 0; i < splitGeometry.length; i++) {
                splitGeometry[i] = GL45.glCreateBuffers();
                GL45.glNamedBufferData(splitGeometry[i],(long)maxIds * faceBytes,GL15.GL_DYNAMIC_DRAW);
            }
            checkGl("GPU face delta initialization");
        } catch (Exception failure) {
            close();
            throw new IllegalStateException("Could not initialize GPU face delta",failure);
        }
    }

    public Counts compare(GpuBuffer oldIds,GpuBuffer oldGeometry,int oldCount,
            GpuBuffer newIds,GpuBuffer newGeometry,int newCount) {
        RenderSystem.assertOnRenderThread();
        if (markProgram == 0 || countProgram == 0) throw new IllegalStateException("GPU face delta is closed");
        if (oldCount < 0 || newCount < 0 || oldCount > maxIds || newCount > maxIds)
            throw new IllegalArgumentException("Face count exceeds identity domain");
        int oldHandle = inputHandle(oldIds,oldCount,Integer.BYTES,"old IDs");
        int newHandle = inputHandle(newIds,newCount,Integer.BYTES,"new IDs");
        int oldFaces = inputHandle(oldGeometry,oldCount,faceBytes,"old geometry");
        int newFaces = inputHandle(newGeometry,newCount,faceBytes,"new geometry");
        lastCounts = null;
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
            checkGl("before GPU face delta");
            for (int buffer : presence) GL45.glNamedBufferSubData(buffer,0,zeroPresence.duplicate());
            GL45.glNamedBufferSubData(counters,0,new int[4]);
            GL20.glUseProgram(markProgram);
            GL30.glUniform1ui(GL20.glGetUniformLocation(markProgram,"MaxId"),maxIds);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,oldCount == 0 ? presence[0] : oldHandle);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,1,presence[0]);
            if (oldCount > 0) {
                GL30.glUniform1ui(GL20.glGetUniformLocation(markProgram,"FaceCount"),oldCount);
                GL43.glDispatchCompute((oldCount + LOCAL_SIZE - 1) / LOCAL_SIZE,1,1);
            }
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,newCount == 0 ? presence[1] : newHandle);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,1,presence[1]);
            if (newCount > 0) {
                GL30.glUniform1ui(GL20.glGetUniformLocation(markProgram,"FaceCount"),newCount);
                GL43.glDispatchCompute((newCount + LOCAL_SIZE - 1) / LOCAL_SIZE,1,1);
            }
            GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
            GL20.glUseProgram(countProgram);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,oldCount == 0 ? presence[0] : oldHandle);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,1,newCount == 0 ? presence[1] : newHandle);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,2,presence[0]);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,3,presence[1]);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,4,counters);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,5,oldCount == 0 ? presence[0] : oldFaces);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,6,newCount == 0 ? presence[1] : newFaces);
            for (int i = 0; i < splitGeometry.length; i++)
                GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,7+i,splitGeometry[i]);
            GL30.glUniform1ui(GL20.glGetUniformLocation(countProgram,"OldCount"),oldCount);
            GL30.glUniform1ui(GL20.glGetUniformLocation(countProgram,"NewCount"),newCount);
            GL30.glUniform1ui(GL20.glGetUniformLocation(countProgram,"MaxId"),maxIds);
            int work = Math.max(oldCount,newCount);
            if (work > 0) GL43.glDispatchCompute((work + LOCAL_SIZE - 1) / LOCAL_SIZE,1,1);
            GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT | GL43.GL_SHADER_STORAGE_BARRIER_BIT);
            int[] counts = new int[4];
            GL45.glGetNamedBufferSubData(counters,0,counts);
            checkGl("GPU face delta readback");
            if (counts[3] != 0 || counts[0] < 0 || counts[1] < 0 || counts[2] < 0
                    || counts[0] + counts[1] != newCount || counts[0] + counts[2] != oldCount)
                throw new IllegalStateException("Invalid GPU face delta counters: stable=" + counts[0]
                    + " added=" + counts[1] + " removed=" + counts[2] + " invalid=" + counts[3]);
            lastCounts = new Counts(counts[0],counts[1],counts[2]);
            return lastCounts;
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

    /** Direct copy into renderable buffers. Null is valid only for an empty class. */
    public void copySplitTo(GpuBuffer stable,GpuBuffer added,GpuBuffer removed) {
        RenderSystem.assertOnRenderThread();
        if (lastCounts == null) throw new IllegalStateException("No completed GPU face delta");
        GpuBuffer[] targets = {stable,added,removed};
        int[] counts = {lastCounts.stable(),lastCounts.added(),lastCounts.removed()};
        for (int i = 0; i < targets.length; i++) {
            int count = counts[i];
            if (count == 0) {
                if (targets[i] != null) throw new IllegalArgumentException("Empty split class has a destination");
                continue;
            }
            GpuBuffer target = targets[i];
            if (!(target instanceof GlBuffer gl) || target.isClosed()
                    || (target.usage() & GpuBuffer.USAGE_COPY_DST) == 0
                    || target.size() < (long)count * faceBytes)
                throw new IllegalArgumentException("Invalid face-delta draw buffer " + i);
            GL45.glCopyNamedBufferSubData(splitGeometry[i],gl.handle(),0,0,(long)count * faceBytes);
        }
        checkGl("GPU face delta draw copy");
    }

    /** Test-only readback of the three classes; production drawing uses copySplitTo. */
    public byte[][] readSplitForTest() {
        RenderSystem.assertOnRenderThread();
        if (lastCounts == null) throw new IllegalStateException("No completed GPU face delta");
        int[] counts = {lastCounts.stable(),lastCounts.added(),lastCounts.removed()};
        byte[][] result = new byte[3][];
        for (int i = 0; i < counts.length; i++) {
            result[i] = new byte[counts[i] * faceBytes];
            if (result[i].length != 0) {
                ByteBuffer bytes = ByteBuffer.allocateDirect(result[i].length);
                GL45.glGetNamedBufferSubData(splitGeometry[i],0,bytes);
                bytes.get(result[i]);
            }
        }
        checkGl("GPU face delta test readback");
        return result;
    }

    private static int inputHandle(GpuBuffer buffer,int count,int stride,String kind) {
        if (count == 0) return 0;
        if (!(buffer instanceof GlBuffer gl) || buffer.isClosed()
                || buffer.size() < (long)count * stride)
            throw new IllegalArgumentException(kind + " needs a live OpenGL buffer of the required size");
        return gl.handle();
    }

    private static int compile(String path) throws Exception {
        return compile(path,GpuCloudGeneration.BYTES_PER_INSTANCE);
    }

    private static int compile(String path,int faceBytes) throws Exception {
        String source;
        try (InputStream input = GpuFaceDelta.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new java.io.FileNotFoundException(path);
            source = new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
        if (faceBytes == CloudVertexFormat.BYTES_PER_INSTANCE_ALPHA)
            source = source.replace("#define FACE_ALPHA 0","#define FACE_ALPHA 1");
        int shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        try {
            GL20.glShaderSource(shader,source);
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS) == 0)
                throw new IllegalStateException(path + ": " + GL20.glGetShaderInfoLog(shader));
            int program = GL20.glCreateProgram();
            GL20.glAttachShader(program,shader);
            GL20.glLinkProgram(program);
            if (GL20.glGetProgrami(program,GL20.GL_LINK_STATUS) == 0) {
                String log = GL20.glGetProgramInfoLog(program);
                GL20.glDeleteProgram(program);
                throw new IllegalStateException(path + ": " + log);
            }
            return program;
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
        if (markProgram != 0) { GL20.glDeleteProgram(markProgram); markProgram = 0; }
        if (countProgram != 0) { GL20.glDeleteProgram(countProgram); countProgram = 0; }
        for (int i = 0; i < presence.length; i++)
            if (presence[i] != 0) { GL15.glDeleteBuffers(presence[i]); presence[i] = 0; }
        for (int i = 0; i < splitGeometry.length; i++)
            if (splitGeometry[i] != 0) { GL15.glDeleteBuffers(splitGeometry[i]); splitGeometry[i] = 0; }
        if (counters != 0) { GL15.glDeleteBuffers(counters); counters = 0; }
        lastCounts = null;
    }
}

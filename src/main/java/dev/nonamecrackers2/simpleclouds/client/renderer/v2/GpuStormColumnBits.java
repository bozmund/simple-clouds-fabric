package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL45;

/** Compresses GPU opaque +Y faces into one occupancy bit per storm column.
 * Only the bounded bitset crosses to the CPU for local weather classification. */
public final class GpuStormColumnBits implements AutoCloseable {
    private static final int LOCAL_SIZE = 256;
    private static final int BINDINGS = 3;
    private final int maxColumns;
    private int program;
    private int bits;
    private int invalid;

    public GpuStormColumnBits(int maxColumns) {
        RenderSystem.assertOnRenderThread();
        if (!GpuCloudGeneration.isOpenGLBackend() || maxColumns <= 0 || maxColumns > 32 * 32)
            throw new IllegalArgumentException("Unsupported storm column capacity");
        this.maxColumns = maxColumns;
        try {
            if (GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS) < BINDINGS)
                throw new IllegalStateException("Not enough shader storage bindings");
            program = compile("assets/simpleclouds/shaders/compute/storm_column_bits.comp");
            bits = GL45.glCreateBuffers();
            GL45.glNamedBufferData(bits,((long)maxColumns + 31) / 32 * Integer.BYTES,GL15.GL_DYNAMIC_DRAW);
            invalid = GL45.glCreateBuffers();
            GL45.glNamedBufferData(invalid,Integer.BYTES,GL15.GL_DYNAMIC_DRAW);
            checkGl("storm column bitset initialization");
        } catch (Exception failure) {
            close();
            throw new IllegalStateException("Could not initialize GPU storm column bitset",failure);
        }
    }

    public GpuStormColumns.Result fromFaceIds(GpuCloudGeneration source,
            int x0,int y0,int z0,int x1,int y1,int z1,int lod,int cameraGridY,
            float cameraX,float cameraZ,List<CpuCloudGenerator.CloudLayerGroup> groups,
            List<CpuCloudGenerator.RegionMask> regions) {
        RenderSystem.assertOnRenderThread();
        if (program == 0) throw new IllegalStateException("GPU storm column bitset is closed");
        if (lod <= 0 || x1 <= x0 || y1 <= y0 || z1 <= z0
                || (x1-x0)%lod != 0 || (y1-y0)%lod != 0 || (z1-z0)%lod != 0)
            throw new IllegalArgumentException("Invalid storm column bounds");
        int dx=(x1-x0)/lod,dy=(y1-y0)/lod,dz=(z1-z0)/lod;
        int columns=Math.multiplyExact(dx,dz), words=(columns+31)/32;
        if (columns > maxColumns || source.completedCellCount() != dx*dy*dz)
            throw new IllegalArgumentException("GPU storm column shape differs from completed dispatch");
        int faceCount=source.completedOpaqueFaceCount();
        int previousProgram=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int generic=GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        int[] bound=new int[BINDINGS];
        long[] offsets=new long[BINDINGS],sizes=new long[BINDINGS];
        for (int i=0;i<BINDINGS;i++) {
            bound[i]=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,i);
            offsets[i]=GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START,i);
            sizes[i]=GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE,i);
        }
        int[] occupied=new int[words];
        try {
            checkGl("before GPU storm column reduction");
            GL45.glNamedBufferSubData(bits,0,new int[(maxColumns+31)/32]);
            GL45.glNamedBufferSubData(invalid,0,new int[]{0});
            if (faceCount > 0) {
                GL20.glUseProgram(program);
                GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,source.opaqueFaceIdBufferHandle());
                GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,1,bits);
                GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,2,invalid);
                GL30.glUniform1ui(GL20.glGetUniformLocation(program,"FaceCount"),faceCount);
                GL30.glUniform1ui(GL20.glGetUniformLocation(program,"XCells"),dx);
                GL30.glUniform1ui(GL20.glGetUniformLocation(program,"YCells"),dy);
                GL30.glUniform1ui(GL20.glGetUniformLocation(program,"ZCells"),dz);
                GL20.glUniform1i(GL20.glGetUniformLocation(program,"Y0"),y0);
                GL20.glUniform1i(GL20.glGetUniformLocation(program,"Lod"),lod);
                GL20.glUniform1i(GL20.glGetUniformLocation(program,"CameraGridY"),cameraGridY);
                GL43.glDispatchCompute((faceCount+LOCAL_SIZE-1)/LOCAL_SIZE,1,1);
                GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT | GL43.GL_SHADER_STORAGE_BARRIER_BIT);
                GL45.glGetNamedBufferSubData(bits,0,occupied);
                int[] bad=new int[1];
                GL45.glGetNamedBufferSubData(invalid,0,bad);
                if (bad[0] != 0) throw new IllegalStateException("GPU emitted " + bad[0] + " invalid storm face IDs");
            }
            checkGl("GPU storm column reduction");
        } finally {
            for (int i=0;i<BINDINGS;i++) {
                if (bound[i] != 0 && sizes[i] > 0)
                    GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,i,bound[i],offsets[i],sizes[i]);
                else GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,i,bound[i]);
            }
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,generic);
            GL20.glUseProgram(previousProgram);
        }
        byte[] stormColumns=new byte[columns];
        boolean[] marked=new boolean[columns];
        for (int index=0;index<columns;index++) {
            if ((occupied[index>>5] & (1 << (index&31))) == 0) continue;
            int ix=index/dz,iz=index%dz;
            float centerX=x0+(ix+.5f)*lod, centerZ=z0+(iz+.5f)*lod;
            int group=GpuStormColumns.selectedGroup(centerX,centerZ,regions);
            if (group < 0 || group >= groups.size())
                throw new IllegalArgumentException("GPU storm column has no valid formation group");
            if (groups.get(group).stormType()) { marked[index]=true; stormColumns[index]=1; }
        }
        float coverage=StormCoverage.contribution(marked,dx,dz,x0,z0,lod,cameraX,cameraZ);
        return new GpuStormColumns.Result(stormColumns,dx,dz,coverage);
    }

    private static int compile(String path) throws Exception {
        String source;
        try (InputStream input=GpuStormColumnBits.class.getClassLoader().getResourceAsStream(path)) {
            if (input==null) throw new java.io.FileNotFoundException(path);
            source=new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
        int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        try {
            GL20.glShaderSource(shader,source);
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0)
                throw new IllegalStateException(path+": "+GL20.glGetShaderInfoLog(shader));
            int result=GL20.glCreateProgram();
            GL20.glAttachShader(result,shader);
            GL20.glLinkProgram(result);
            if (GL20.glGetProgrami(result,GL20.GL_LINK_STATUS)==0) {
                String log=GL20.glGetProgramInfoLog(result);
                GL20.glDeleteProgram(result);
                throw new IllegalStateException(path+": "+log);
            }
            return result;
        } finally { GL20.glDeleteShader(shader); }
    }

    private static void checkGl(String stage) {
        int error=GL11.glGetError();
        if (error!=GL11.GL_NO_ERROR)
            throw new IllegalStateException(stage+": GL 0x"+Integer.toHexString(error));
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (program!=0) { GL20.glDeleteProgram(program); program=0; }
        if (bits!=0) { GL15.glDeleteBuffers(bits); bits=0; }
        if (invalid!=0) { GL15.glDeleteBuffers(invalid); invalid=0; }
    }
}

package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.nonamecrackers2.simpleclouds.client.shader.compute.ComputeShader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.*;
import java.io.IOException;

/** Converts original cloud-unit records to 26.3 draw records entirely on GPU.
 * No noise calculation, face-delta fade, queue or geometry readback lives here. */
public final class OriginalMeshDrawAdapter implements AutoCloseable {
    private final ComputeShader shader;
    public OriginalMeshDrawAdapter(ResourceManager resources) throws IOException {
        shader=ComputeShader.loadShader(Identifier.parse("simpleclouds:original_draw_adapter"),resources,256,1,1);
    }
    public int convert(int sourceBuffer, int count, int targetBuffer, boolean transparent,
            float cloudScale, float cloudHeight) {
        RenderSystem.assertOnRenderThread();
        if (count<0 || !Float.isFinite(cloudScale) || cloudScale<=0 || !Float.isFinite(cloudHeight))
            throw new IllegalArgumentException("Invalid original draw conversion parameters");
        int faces=count;
        long sourceBytes=(long)count*24;
        long targetBytes=(long)faces*24;
        if(count==0) return 0;
        if(sourceBuffer==targetBuffer || !GL15.glIsBuffer(sourceBuffer) || !GL15.glIsBuffer(targetBuffer))
            throw new IllegalArgumentException("Draw conversion requires distinct live buffers");
        if(GL45.glGetNamedBufferParameteri64(sourceBuffer,GL15.GL_BUFFER_SIZE)<sourceBytes
                || GL45.glGetNamedBufferParameteri64(targetBuffer,GL15.GL_BUFFER_SIZE)<targetBytes)
            throw new IllegalArgumentException("Original draw conversion exceeds buffer capacity");
        int generic=GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        int[] bound=new int[2]; long[] start=new long[2],size=new long[2];
        for(int i=0;i<2;i++) {
            bound[i]=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,i);
            start[i]=GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START,i);
            size[i]=GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE,i);
        }
        try {
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,sourceBuffer);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,1,targetBuffer);
            shader.forUniform("ElementCount",(p,l)->GL41.glProgramUniform1ui(p,l,count));
            shader.forUniform("Transparent",(p,l)->GL41.glProgramUniform1i(p,l,transparent?1:0));
            shader.forUniform("CloudScale",(p,l)->GL41.glProgramUniform1f(p,l,cloudScale));
            shader.forUniform("CloudHeight",(p,l)->GL41.glProgramUniform1f(p,l,cloudHeight));
            shader.dispatchAndWait((count+255)/256,1,1);
            GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT
                | GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
            return faces;
        } finally {
            for(int i=0;i<2;i++) {
                if(bound[i]!=0 && size[i]>0) GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,i,bound[i],start[i],size[i]);
                else GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,i,bound[i]);
            }
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,generic);
        }
    }
    @Override public void close() { shader.close(); }
}

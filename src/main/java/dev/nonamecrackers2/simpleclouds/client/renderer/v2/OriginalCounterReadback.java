package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.opengl.*;

/** Snapshot counters immediately after compute, before later rendering commands.
 * Coherent persistent host reads still require a completed GPU fence (GL4.5 §6.2).
 * This never skips a publication or changes the generation cadence. */
public final class OriginalCounterReadback implements AutoCloseable {
    private final int bytes,buffer;
    private final boolean transparent;
    private final ByteBuffer mapped;
    private long fence;
    private boolean ready,closed;
    private final boolean profiling="1".equals(System.getenv("SIMPLECLOUDS_PROFILE"));
    private final boolean earlyFlush="1".equals(System.getenv("SIMPLECLOUDS_DEV"))
        && "1".equals(System.getenv("SIMPLECLOUDS_TEST_COUNTER_EARLY_FLUSH"));
    private long capturedNs, captureAgeNs, waitNs, maxWaitNs;
    private long captureCpuNs, flushCpuNs, maxCaptureCpuNs;
    private int samples, immediate;

    public OriginalCounterReadback(int bytes,boolean transparent) {
        if(bytes<=0 || bytes%4!=0)throw new IllegalArgumentException("Invalid counter size");
        this.bytes=bytes;this.transparent=transparent;
        buffer=GL45.glCreateBuffers();
        int flags=GL30.GL_MAP_READ_BIT|GL44.GL_MAP_PERSISTENT_BIT|GL44.GL_MAP_COHERENT_BIT;
        GL45.glNamedBufferStorage(buffer,(long)bytes*(transparent?2:1),flags);
        ByteBuffer view=GL45.glMapNamedBufferRange(buffer,0,(long)bytes*(transparent?2:1),flags);
        if(view==null){GL15.glDeleteBuffers(buffer);throw new IllegalStateException("Counter snapshot mapping failed");}
        mapped=view.order(ByteOrder.nativeOrder());
    }
    public void capture(int opaque,int translucent) {
        if(closed || fence!=0)throw new IllegalStateException("Counter snapshot still pending or closed");
        long captureStart=profiling?System.nanoTime():0;
        ready=false;
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        GL45.glCopyNamedBufferSubData(opaque,buffer,0,0,bytes);
        if(transparent)GL45.glCopyNamedBufferSubData(translucent,buffer,0,bytes,bytes);
        fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);
        if(fence==0)throw new IllegalStateException("Counter snapshot fence failed");
        if(profiling)capturedNs=System.nanoTime();
        // Diagnostic: submit this fence before subsequent drawing, without
        // replacing the completion wait or changing publication frequency.
        if(earlyFlush) {
            long flushStart=profiling?System.nanoTime():0;
            GL11.glFlush();
            if(profiling)flushCpuNs+=System.nanoTime()-flushStart;
        }
        if(profiling) {
            long duration=System.nanoTime()-captureStart;
            captureCpuNs+=duration;maxCaptureCpuNs=Math.max(maxCaptureCpuNs,duration);
        }
    }
    public void await() {
        if(closed)throw new IllegalStateException("Closed counter snapshot");
        if(ready)return;
        if(fence==0)throw new IllegalStateException("No counter snapshot captured");
        long waitStart=profiling?System.nanoTime():0;
        int result=GL32.glClientWaitSync(fence,GL32.GL_SYNC_FLUSH_COMMANDS_BIT,0);
        boolean alreadyReady=result==GL32.GL_ALREADY_SIGNALED || result==GL32.GL_CONDITION_SATISFIED;
        for(int attempt=0;result==GL32.GL_TIMEOUT_EXPIRED && attempt<10;attempt++)
            result=GL32.glClientWaitSync(fence,0,1_000_000_000L);
        if(result!=GL32.GL_ALREADY_SIGNALED && result!=GL32.GL_CONDITION_SATISFIED)
            throw new IllegalStateException("Counter snapshot wait failed: "+result);
        GL32.glDeleteSync(fence);fence=0;ready=true;
        if(profiling) {
            long duration=System.nanoTime()-waitStart;
            waitNs+=duration;maxWaitNs=Math.max(maxWaitNs,duration);
            captureAgeNs+=waitStart-capturedNs;
            if(alreadyReady)immediate++;
            if(++samples==24) {
                org.slf4j.LoggerFactory.getLogger(OriginalCounterReadback.class).info(
                    "[COUNTER-WAIT] samples={} immediatelyReady={} captureToWaitMeanMs={} waitMeanMs={} waitMaxMs={} earlyFlush={} captureCpuMeanMs={} captureCpuMaxMs={} flushCpuMeanMs={}",
                    samples,immediate,captureAgeNs/(samples*1_000_000.0),waitNs/(samples*1_000_000.0),maxWaitNs/1_000_000.0,earlyFlush,
                    captureCpuNs/(samples*1_000_000.0),maxCaptureCpuNs/1_000_000.0,flushCpuNs/(samples*1_000_000.0));
                samples=immediate=0;captureAgeNs=waitNs=maxWaitNs=0;
                captureCpuNs=flushCpuNs=maxCaptureCpuNs=0;
            }
        }
    }
    public ByteBuffer counts(boolean translucent) {
        if(closed || !ready || translucent && !transparent)throw new IllegalStateException("Counter snapshot unavailable");
        return mapped.slice(translucent?bytes:0,bytes).asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    }
    @Override public void close() {
        if(closed)return;
        if(fence!=0){GL32.glDeleteSync(fence);fence=0;}
        GL45.glUnmapNamedBuffer(buffer);GL15.glDeleteBuffers(buffer);closed=true;ready=false;
    }
}

package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Opt-in development timings. Timestamp results are read only after availability;
 * no finish, blocking query read, mesh readback or production scheduling change. */
public final class OriginalCloudFrameTimings implements AutoCloseable {
    private static final Logger LOG=LoggerFactory.getLogger(OriginalCloudFrameTimings.class);
    private static final String[] STAGES={"generation","opaque","transparency","storm-shadow","storm-fog","depth-capture"};
    private static final int MARKS=STAGES.length+1, RING=8;
    private final int[][] queries=new int[RING][MARKS];
    private final long[][] cpu=new long[RING][MARKS];
    private final boolean[] pending=new boolean[RING];
    private final double[] gpuSum=new double[STAGES.length], cpuSum=new double[STAGES.length], cpuMax=new double[STAGES.length];
    private int next, active=-1, samples, skipped;
    private long lastFrameNs;
    private final double[] frameMs=new double[120];
    private int frameSamples;

    public static OriginalCloudFrameTimings createIfEnabled() {
        return "1".equals(System.getenv("SIMPLECLOUDS_PROFILE"))?new OriginalCloudFrameTimings():null;
    }
    private OriginalCloudFrameTimings() {
        for(int[] row:queries) for(int i=0;i<MARKS;i++) row[i]=GL15.glGenQueries();
    }
    public void begin() {
        long now=System.nanoTime();
        if(lastFrameNs!=0) {
            frameMs[frameSamples++]=(now-lastFrameNs)/1_000_000.0;
            if(frameSamples==frameMs.length) {
                double sum=0;for(double value:frameMs) sum+=value;
                java.util.Arrays.sort(frameMs);
                LOG.info("[CLOUD-FRAME] samples={} frameMeanMs={} fpsFromMean={} frameP95Ms={} frameMaxMs={}",
                    frameSamples,sum/frameSamples,1000.0/(sum/frameSamples),frameMs[113],frameMs[119]);
                frameSamples=0;
            }
        }
        lastFrameNs=now;
        active=-1;
        for(int slot=0;slot<RING;slot++) {
            if(!pending[slot] || GL15.glGetQueryObjecti(queries[slot][MARKS-1],GL15.GL_QUERY_RESULT_AVAILABLE)==0) continue;
            long previous=GL33.glGetQueryObjectui64(queries[slot][0],GL15.GL_QUERY_RESULT);
            for(int i=0;i<STAGES.length;i++) {
                long value=GL33.glGetQueryObjectui64(queries[slot][i+1],GL15.GL_QUERY_RESULT);
                double gpuMs=(value-previous)/1_000_000.0;
                double cpuMs=(cpu[slot][i+1]-cpu[slot][i])/1_000_000.0;
                gpuSum[i]+=gpuMs; cpuSum[i]+=cpuMs; cpuMax[i]=Math.max(cpuMax[i],cpuMs);
                previous=value;
            }
            pending[slot]=false;
            if(++samples==120) report();
        }
        if(pending[next]) {skipped++;return;}
        active=next; next=(next+1)%RING;
        mark(0);
    }
    public void mark(int index) {
        if(active<0) return;
        cpu[active][index]=System.nanoTime();
        GL33.glQueryCounter(queries[active][index],GL33.GL_TIMESTAMP);
    }
    public void end() {
        if(active<0) return;
        mark(MARKS-1); pending[active]=true; active=-1;
    }
    private void report() {
        for(int i=0;i<STAGES.length;i++) {
            LOG.info("[CLOUD-TIMING] stage={} samples={} gpuMeanMs={} cpuMeanMs={} cpuMaxMs={} skipped={}",
                STAGES[i],samples,gpuSum[i]/samples,cpuSum[i]/samples,cpuMax[i],skipped);
            gpuSum[i]=cpuSum[i]=cpuMax[i]=0;
        }
        samples=0; skipped=0;
    }
    @Override public void close() {
        for(int[] row:queries) for(int query:row) GL15.glDeleteQueries(query);
    }
}

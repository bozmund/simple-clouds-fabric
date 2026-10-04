package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

/** Opt-in CPU wall timings; nested publication phases deliberately overlap. */
public final class OriginalMeshCpuTimings {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(OriginalMeshCpuTimings.class);
    private static final String[] STAGES={"publication","prepare","dispatch","counter-access","mesh-copy"};
    private final long[] total=new long[STAGES.length],maximum=new long[STAGES.length];
    private final int[] calls=new int[STAGES.length];
    private int frames;
    public static OriginalMeshCpuTimings createIfEnabled() {
        return "1".equals(System.getenv("SIMPLECLOUDS_PROFILE"))?new OriginalMeshCpuTimings():null;
    }
    public void end(int stage,long start) {
        long elapsed=System.nanoTime()-start;
        total[stage]+=elapsed;maximum[stage]=Math.max(maximum[stage],elapsed);calls[stage]++;
    }
    public void frame() {
        if(++frames!=120)return;
        for(int i=0;i<STAGES.length;i++) {
            LOG.info("[MESH-CPU] stage={} frames={} calls={} perFrameMs={} perCallMs={} maxCallMs={}",
                STAGES[i],frames,calls[i],total[i]/1_000_000.0/frames,
                calls[i]==0?0.0:total[i]/1_000_000.0/calls[i],maximum[i]/1_000_000.0);
            total[i]=maximum[i]=0;calls[i]=0;
        }
        frames=0;
    }
}

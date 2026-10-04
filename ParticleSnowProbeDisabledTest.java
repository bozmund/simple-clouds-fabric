import dev.nonamecrackers2.simpleclouds.client.ParticleSnowRenderProbe;
public final class ParticleSnowProbeDisabledTest {
    public static void main(String[] args) {
        if(ParticleSnowRenderProbe.ENABLED)throw new AssertionError("fixture unexpectedly enabled");
        if(ParticleSnowRenderProbe.count(null)!=0)throw new AssertionError("disabled counter not inert");
        ParticleSnowRenderProbe.extracted(null,null,0);
        ParticleSnowRenderProbe.submitted(null);
        ParticleSnowRenderProbe.clear(null);
        System.out.println("PASS particle snow evidence fixture inert without every explicit opt-in");
    }
}

package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;

/** Indexed OIT experiment (developer opt-in only); normal play uses the original two-pass draw. */
public final class IndexedCloudBlend implements AutoCloseable {
    private static IndexedCloudBlend active;
    private static boolean announced;
    private final Thread owner=Thread.currentThread();
    private boolean bound,closed;
    private IndexedCloudBlend() {}
    public static boolean scoped() {return active!=null;}
    public static boolean enabled() {
        // Readback diagnostics remain explicitly opted in, independently of the
        // production renderer's capability-based selection.
        return "1".equals(System.getenv("SIMPLECLOUDS_DEV"))
            && "1".equals(System.getenv("SIMPLECLOUDS_TEST_OIT_MRT"));
    }
    public static boolean select(boolean capable,boolean developer,String override) {
        // Explicit developer opt-in only: inside storm clouds the indexed path leaves the
        // cloud geometry bright and seamed where the original two-pass draw is uniformly
        // dark (matched 1.20.1 STORM S3 comparison, 2026-10-02).
        return capable && developer && "1".equals(override);
    }
    public static boolean useIndexedBackend() {
        com.mojang.blaze3d.systems.RenderSystem.assertOnRenderThread();
        boolean capable=GpuCloudGeneration.isOpenGLBackend() && GL.getCapabilities().OpenGL40;
        boolean developer="1".equals(System.getenv("SIMPLECLOUDS_DEV"));
        String override=System.getenv("SIMPLECLOUDS_TEST_OIT_MRT");
        boolean selected=select(capable,developer,override);
        org.slf4j.LoggerFactory.getLogger(IndexedCloudBlend.class).info(
            "[OIT-BACKEND] indexed={} capable={} developerOverride={}",selected,capable,developer?override:"none");
        return selected;
    }
    public static IndexedCloudBlend open() {
        com.mojang.blaze3d.systems.RenderSystem.assertOnRenderThread();
        if(active!=null || !GpuCloudGeneration.isOpenGLBackend() || !GL.getCapabilities().OpenGL40)
            throw new IllegalStateException("Indexed cloud blend requires exclusive capable OpenGL scope");
        return active=new IndexedCloudBlend();
    }
    /** Called after the backend establishes its declared common blend state. */
    public static void afterBind(String label) {
        var scope=active;if(scope==null || scope.owner!=Thread.currentThread())return;
        if(!label.equals("simpleclouds:core/original_transparency_mrt"))
            throw new IllegalStateException("Unexpected pipeline inside indexed cloud scope: "+label);
        GL40.glBlendFuncSeparatei(1,GL11.GL_ZERO,GL11.GL_ONE_MINUS_SRC_COLOR,GL11.GL_ZERO,GL11.GL_ONE_MINUS_SRC_COLOR);
        scope.bound=true;
        if(!announced) {
            announced=true;
            org.slf4j.LoggerFactory.getLogger(IndexedCloudBlend.class).info("[OIT-MRT] original indexed RGBA16F/R8 attachments active; geometry drawn once");
        }
    }
    @Override public void close() {
        if(closed)return;
        if(owner!=Thread.currentThread() || active!=this)throw new IllegalStateException("Indexed scope ownership violation");
        closed=true;active=null;
        // Restore the CURRENT backend pipeline's declared ONE/ONE state, not
        // a prior pipeline's state: its global state cache must remain truthful.
        if(bound)GL40.glBlendFuncSeparatei(1,GL11.GL_ONE,GL11.GL_ONE,GL11.GL_ONE,GL11.GL_ONE);
        if(!bound)throw new IllegalStateException("Indexed cloud backend bind hook was not exercised");
    }
    public static void verifyRestored() {
        if(active!=null || GL30.glGetIntegeri(GL14.GL_BLEND_SRC_RGB,1)!=GL11.GL_ONE
                || GL30.glGetIntegeri(GL14.GL_BLEND_DST_RGB,1)!=GL11.GL_ONE
                || GL30.glGetIntegeri(GL14.GL_BLEND_SRC_ALPHA,1)!=GL11.GL_ONE
                || GL30.glGetIntegeri(GL14.GL_BLEND_DST_ALPHA,1)!=GL11.GL_ONE)
            throw new IllegalStateException("Indexed cloud blend leaked backend state");
    }
}

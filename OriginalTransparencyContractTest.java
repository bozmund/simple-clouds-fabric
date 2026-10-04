import java.nio.file.Files;
import java.nio.file.Path;

/** Architecture guard only; real blending is exercised by OriginalComputeContextTest. */
public class OriginalTransparencyContractTest {
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static String read(String path) throws Exception { return Files.readString(Path.of(path)); }
    public static void main(String[] args) throws Exception {
        String root="src/main/";
        String pipeline=read(root+"java/dev/nonamecrackers2/simpleclouds/client/renderer/v2/CloudsDrawPipeline.java");
        String renderer=read(root+"java/dev/nonamecrackers2/simpleclouds/client/renderer/SimpleCloudsRenderer.java");
        String shaders=root+"resources/assets/simpleclouds/shaders/core/";
        String vertex=read(shaders+"clouds_transparency.vsh");
        String fragment=read(shaders+"clouds_transparency.fsh");
        String composite=read(shaders+"original_transparency_composite.fsh");
        require(vertex.contains("vertexDistance = length((ModelViewMat * finalPos).xyz)"),"Original full view distance missing");
        require(fragment.contains("premul.a * 3000.0 * pow(1.0 - z, 3.0)")
            && fragment.contains("accumColor = premul * weight")
            && fragment.contains("revealage = premul.a"),"Original OIT outputs/weights changed");
        require(composite.contains("accum.rgb / max(accum.a, 0.00001)")
            && composite.contains("vec4(avg, 1.0 - revealage)"),"Original OIT resolve missing");
        int start=pipeline.indexOf("this.transparencyPipeline =");
        int end=pipeline.indexOf(".build();",start);
        String accumulation=pipeline.substring(start,end);
        int revealStart=pipeline.indexOf("this.transparencyRevealagePipeline =");
        String revealPipeline=pipeline.substring(revealStart,pipeline.indexOf(".build();",revealStart));
        require(accumulation.contains("ORIGINAL_ACCUM_ONLY") && revealPipeline.contains("ORIGINAL_REVEALAGE_ONLY"),"Separate original accumulation/revealage passes missing");
        require(accumulation.contains("GpuFormat.RGBA16_FLOAT") && revealPipeline.contains("GpuFormat.R8_UNORM"),"Original attachment formats lost");
        require(accumulation.contains("BlendFactor.ONE, BlendFactor.ONE")
            && revealPipeline.contains("BlendFactor.ZERO, BlendFactor.ONE_MINUS_SRC_COLOR"),"Original independent blend states lost");
        require(!accumulation.contains("BlendFunction.TRANSLUCENT"),"Standard alpha substitution returned");
        require(accumulation.contains(".withCull(true)") && revealPipeline.contains(".withCull(true)"),
            "Original transparent cube culling disabled; double-sided overdraw returned");
        require(accumulation.contains("CloudVertexFormat.CUBE_INSTANCE_FORMAT")
            && revealPipeline.contains("CloudVertexFormat.CUBE_INSTANCE_FORMAT")
            && vertex.contains("Position * Radius + SidePos + Offset") && !vertex.contains("applySideTransform"),
            "Original compact cube instancing replaced with expanded face instances");
        String compactPipeline=pipeline.replaceAll("\\s+", "");
        require(compactPipeline.contains("CUBE_VERTICES={-1,-1,-1,1,-1,-1,1,1,-1,-1,1,-1,1,-1,1,1,1,1,-1,1,1,-1,-1,1}")
            && compactPipeline.contains("CUBE_INDICES={0,1,2,0,2,3,4,7,6,4,6,5,7,0,3,7,3,6,1,4,5,1,5,2,1,0,7,1,7,4,5,6,3,5,3,2}"),
            "Cube coordinates/triangulation/winding diverged from original InstanceableMesh.defaultCube");
        int chunkStart=pipeline.indexOf("public void drawTransparencyClouds(Matrix4f viewMatrix, GpuBuffer instances, int count, float alpha,",pipeline.indexOf("public void drawTransparencyClouds(")+1);
        int chunkEnd=pipeline.indexOf("\n\t}\n",chunkStart)+4;
        String chunk=pipeline.substring(chunkStart,chunkEnd);
        require(chunk.contains("transparentDraws.add(new TransparentDraw") && !chunk.contains("createRenderPass"),
            "Per-chunk framebuffer churn returned");
        int resolveStart=pipeline.indexOf("public void endTransparency()");
        String resolve=pipeline.substring(resolveStart,chunkStart);
        require(resolve.contains("for (int attachment=0;attachment<(mrt?1:2);attachment++)")
            && resolve.contains("this.transparencyMrtPipeline == null || parity != null")
            && resolve.contains("this.drawTransparencyAttachments(encoder, main, false)")
            && resolve.contains("for (TransparentDraw draw : this.transparentDraws)"),"Whole-field two-pass draw lifecycle missing");
        int mrtStart=pipeline.indexOf("this.transparencyMrtPipeline =");
        String mrt=pipeline.substring(mrtStart,pipeline.indexOf(".build();",mrtStart));
        require(mrt.contains("!IndexedCloudBlend.useIndexedBackend() ? null") && mrt.contains(".withColorTargetState(0,")
            && mrt.contains(".withColorTargetState(1,") && mrt.contains("GpuFormat.RGBA16_FLOAT")
            && mrt.contains("GpuFormat.R8_UNORM") && !mrt.contains("withShaderDefine"),"Capability-selected original dual-output MRT layout missing");
        String indexed=read(root+"java/dev/nonamecrackers2/simpleclouds/client/renderer/v2/IndexedCloudBlend.java");
        require(indexed.contains("GL11.GL_ZERO,GL11.GL_ONE_MINUS_SRC_COLOR")
            && indexed.contains("GL11.GL_ONE,GL11.GL_ONE,GL11.GL_ONE,GL11.GL_ONE")
            && resolve.contains("IndexedCloudBlend.open()"),"Scoped original revealage blend/backend-state restore missing");
        require(resolve.contains("this.cubeVertexBuffer.slice()") && resolve.contains("CUBE_INDICES.length"),
            "Transparent cubes no longer use original indexed mesh");
        int begin=renderer.indexOf("this.drawPipeline.beginTransparency()");
        int draw=renderer.indexOf("this.drawPipeline.drawTransparencyClouds",begin);
        int finish=renderer.indexOf("this.drawPipeline.endTransparency()",draw);
        require(begin>=0 && draw>begin && finish>draw && renderer.substring(draw,finish).contains("finally"),"Per-frame accumulation lifecycle missing");
        require(pipeline.contains("this.transparencyAccum.destroyBuffers()")
            && pipeline.contains("this.transparencyRevealage.destroyBuffers()"),"Attachment cleanup missing");
        require(indexed.contains("GpuCloudGeneration.isOpenGLBackend() && GL.getCapabilities().OpenGL40")
            && indexed.contains("select(capable,developer,override)"),"Indexed backend capability guard missing");
        System.out.println("PASS original transparency source contract: original equations/formats/culling, capability-selected indexed MRT and two-pass fallback, resolve/lifetime; GPU/runtime validation separate");
    }
}

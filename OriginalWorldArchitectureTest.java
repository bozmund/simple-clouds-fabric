import java.nio.file.Files;
import java.nio.file.Path;

/** Source guard for architecture, not a substitute for actual GPU/game tests. */
public class OriginalWorldArchitectureTest {
    static void require(boolean condition,String message) {if(!condition) throw new AssertionError(message);}
    public static void main(String[] args) throws Exception {
        Path root=Path.of("src/main/java/dev/nonamecrackers2/simpleclouds/client");
        String world=Files.readString(root.resolve("renderer/SimpleCloudsRenderer.java"));
        String preview=Files.readString(root.resolve("renderer/v2/PreviewDrawPipeline.java"));
        for(String removed:new String[]{"CpuCloudGenerator","ChunkJob","ChunkResult","chunkCaches",
                "startChunkWorkerPool","chunkWorkerLoop","disableGpuWorld","CPU_ENQUEUE_BUDGET",
                "GPU_WORLD_EXPERIMENT","prepareBatch(","previousBatch","gpuPostPool","StormFogMap"})
            require(!world.contains(removed),"Legacy world architecture returned: "+removed);
        for(String removed:new String[]{"CpuCloudGenerator","ChunkBufferPool","allocateDirect","generator.generate("})
            require(!preview.contains(removed),"CPU preview returned: "+removed);
        require(world.contains("this.meshGenerator.genTick("),"World does not call original scheduler");
        require(world.contains("this.meshGenerator.worldTick()"),"World does not update original chunk fade");
        require(world.contains("case TARGET_FPS") && world.contains("case STATIC") && world.contains("case DYNAMIC"),
            "Original cadence configuration modes missing");
        require(preview.contains("generator.generateMesh()") && preview.contains("createSingleRegion(type)"),
            "Preview does not use original generator");
        require(world.contains("this.meshGenerator.getCompletedGenerationCycles()"),"Diagnostic uses substitute counters");
        String draw=Files.readString(root.resolve("renderer/v2/CloudsDrawPipeline.java"));
        require(!draw.contains("StormFogMap"),"CPU coverage map returned to live fog draw");
        require(draw.contains("OriginalStormFogUniforms.write") && draw.contains("renderOriginalStormShadow"),
            "Original GPU storm producer/consumer missing");
        System.out.println("PASS: main/preview use original GPU entry points; no legacy worker, scheduler, fallback or CPU preview path");
    }
}

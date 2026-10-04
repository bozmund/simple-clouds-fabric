package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.nonamecrackers2.simpleclouds.client.mesh.generator.CloudMeshGenerator;
import dev.nonamecrackers2.simpleclouds.client.mesh.generator.SingleRegionCloudMeshGenerator;
import dev.nonamecrackers2.simpleclouds.common.cloud.CloudType;
import dev.nonamecrackers2.simpleclouds.client.mesh.chunk.MeshChunk;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Original single-region GPU preview, independent depth and weighted transparency.
 * No CPU geometry generator or CPU upload copy is used. Export/UI parity is separate. */
public class PreviewDrawPipeline implements AutoCloseable {
	private static final Logger LOGGER=LogManager.getLogger("simpleclouds/Preview");
	private SingleRegionCloudMeshGenerator generator;
	private OriginalCloudDrawBuffers drawBuffers;
	private int instanceCount;
	private boolean loggedFirstDraw;

	public void generateMesh(CloudType type) {
		RenderSystem.assertOnRenderThread();
		if(type==null) {close();return;}
		if(!GpuCloudGeneration.isOpenGLBackend())
			throw new IllegalStateException("Original preview requires OpenGL; no CPU fallback");
		if(generator==null) {
			generator=CloudMeshGenerator.builder().testFacesFacingAway(true).createSingleRegion(type);
			try {
				var resources=Minecraft.getInstance().getResourceManager();
				var initialized=generator.init(resources);
				if(!initialized.getErrors().isEmpty())
					throw new IllegalStateException("Original preview initialization failed: "+initialized.getErrors());
				drawBuffers=new OriginalCloudDrawBuffers(resources);
			} catch(Exception failure) {
				close();
				throw new IllegalStateException("Cannot initialize original GPU preview",failure);
			}
		} else generator.setCloudType(type);
		generator.generateMesh();
		instanceCount=0;
		generator.forRenderableMeshChunks(null,MeshChunk::getOpaqueBuffers,(chunk,buffers)->{
			// Preview records stay in original cloud units, not world blocks.
			var source=drawBuffers.get(buffers,false,1,0);
			instanceCount+=source.count();
		});
		int[] transparentCount={0};
		generator.forRenderableMeshChunks(null,chunk -> chunk.getTransparentBuffers().orElseThrow(),(chunk,buffers)->{
			transparentCount[0]+=drawBuffers.get(buffers,true,1,0).count();
		});
		LOGGER.info("Simple Clouds preview: {} opaque / {} transparent original GPU instances",instanceCount,transparentCount[0]);
	}

	public void draw(CloudsDrawPipeline pipeline,Matrix4f orbitView, Matrix4f projection) {
		if(generator==null || drawBuffers==null) return;
		if(!loggedFirstDraw) {
			loggedFirstDraw=true;
			LOGGER.info("Simple Clouds preview: first draw, {} instances",instanceCount);
		}
		pipeline.beginPreview(projection);
		try {
			generator.forRenderableMeshChunks(null,MeshChunk::getOpaqueBuffers,(chunk,buffers)->{
				var source=drawBuffers.get(buffers,false,1,0);
				pipeline.drawPreview(orbitView,source.buffer(),source.count());
			});
			pipeline.beginTransparency();
			generator.forRenderableMeshChunks(null,chunk -> chunk.getTransparentBuffers().orElseThrow(),(chunk,buffers)->{
				var source=drawBuffers.get(buffers,true,1,0);
				pipeline.drawTransparencyClouds(orbitView,source.buffer(),source.count(),1,0,0,0);
			});
			pipeline.endTransparency();
			pipeline.finishPreview();
		} finally { pipeline.abortPreview(); }
	}
	public int instanceCount() {return instanceCount;}
	@Override public void close() {
		if(drawBuffers!=null) {drawBuffers.close();drawBuffers=null;}
		if(generator!=null) {generator.close();generator=null;}
		instanceCount=0;
		loggedFirstDraw=false;
	}
}

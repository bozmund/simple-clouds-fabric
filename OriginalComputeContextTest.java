import com.google.common.collect.ImmutableMap;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.nonamecrackers2.simpleclouds.client.shader.compute.ComputeShader;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.OriginalMeshDrawAdapter;
import dev.nonamecrackers2.simpleclouds.client.mesh.generator.CloudMeshGenerator;
import dev.nonamecrackers2.simpleclouds.client.mesh.lod.LevelOfDetailConfig;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.*;
import org.lwjgl.sdl.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;

/** Tiny hidden SDL/Intel context; no Minecraft world, profile or CPU generator. */
public class OriginalComputeContextTest {
    static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static ResourceManager resources() {
        return (ResourceManager)Proxy.newProxyInstance(ResourceManager.class.getClassLoader(),
            new Class<?>[]{ResourceManager.class}, (proxy, method, args) -> {
                if (method.getName().equals("getResourceOrThrow")) {
                    Identifier id = (Identifier)args[0];
                    Path path = Path.of("src/main/resources/assets",id.getNamespace(),id.getPath());
                    return new Resource(null, () -> Files.newInputStream(path));
                }
                throw new UnsupportedOperationException(method.getName());
            });
    }
    static List<Long> state() {
        List<Long> result = new ArrayList<>();
        result.add((long)GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM));
        result.add((long)GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING));
        result.add((long)GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE));
        result.add((long)GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING));
        result.add((long)GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING));
        result.add((long)GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY));
        for(int unit=0;unit<GL11.glGetInteger(GL42.GL_MAX_IMAGE_UNITS);unit++)
            for(int field:new int[]{GL42.GL_IMAGE_BINDING_NAME,GL42.GL_IMAGE_BINDING_LEVEL,
                    GL42.GL_IMAGE_BINDING_LAYERED,GL42.GL_IMAGE_BINDING_LAYER,GL42.GL_IMAGE_BINDING_ACCESS,GL42.GL_IMAGE_BINDING_FORMAT})
                result.add((long)GL30.glGetIntegeri(field,unit));
        int count=GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS);
        for(int i=0;i<count;i++) {
            result.add((long)GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,i));
            result.add(GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START,i));
            result.add(GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE,i));
        }
        return result;
    }
    static void buffers(ComputeShader shader) {
        for(String name:List.of("TotalSides","SidesPerChunk","TotalTransparentCubes","TransparentCubesPerChunk"))
            shader.createAndBindSSBO(name,GL15.GL_DYNAMIC_COPY).allocateBuffer(4);
        shader.createAndBindSSBO("SideInfoBuffer",GL15.GL_DYNAMIC_COPY).allocateBuffer(512*6*24);
        shader.createAndBindSSBO("TransparentCubeInfoBuffer",GL15.GL_DYNAMIC_COPY).allocateBuffer(512*24);
        shader.createAndBindSSBO("NoiseLayers",GL15.GL_DYNAMIC_DRAW).allocateBuffer(32);
        shader.createAndBindSSBO("LayerGroupings",GL15.GL_DYNAMIC_DRAW).allocateBuffer(24);
        shader.getShaderStorageBuffer("LayerGroupings").writeData(b -> {
            b.putInt(0).putInt(1).putFloat(0).putFloat(0).putFloat(1).putFloat(2);
        },24,false);
        shader.forUniform("TestFacesFacingAway",(p,l)->GL41.glProgramUniform1i(p,l,1));
        shader.forUniform("FadeStart",(p,l)->GL41.glProgramUniform1f(p,l,10000));
        shader.forUniform("FadeEnd",(p,l)->GL41.glProgramUniform1f(p,l,20000));
    }
    static void run() throws Exception {
        var options=ImmutableMap.of("TYPE","1","FADE_NEAR_ORIGIN","0","STYLE","0","TRANSPARENCY","1","FIXED_SECTION_SIZE","0");
        int sentinel=GL45.glCreateBuffers();
        int alignment=GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT);
        GL45.glNamedBufferData(sentinel,alignment+16L,GL15.GL_DYNAMIC_DRAW);
        GL45.glNamedBufferSubData(sentinel,alignment,new int[]{12345,67890,123,456});
        int slot=GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS)-1;
        GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,slot,sentinel,alignment,16);
        int sentinelTexture=GL45.glCreateTextures(GL30.GL_TEXTURE_2D_ARRAY);
        GL45.glTextureStorage3D(sentinelTexture,1,GL30.GL_R32F,2,2,2);
        int imageUnit=GL11.glGetInteger(GL42.GL_MAX_IMAGE_UNITS)-1;
        GL42.glBindImageTexture(imageUnit,sentinelTexture,0,false,1,GL15.GL_READ_ONLY,GL30.GL_R32F);
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY,sentinelTexture);
        var before=state();
        for(String type:List.of("0","1"))
            for(String shaded:List.of("0","1"))
                for(String fixed:List.of("0","1")) {
                    // SingleRegionCloudMeshGenerator always passes fadeNearOrigin=false.
                    var variant=ImmutableMap.of("TYPE",type,"FADE_NEAR_ORIGIN",type.equals("0")?"1":"0","STYLE",shaded,
                        "TRANSPARENCY","1","FIXED_SECTION_SIZE",fixed);
                    try(var check=ComputeShader.loadShader(Identifier.parse("simpleclouds:original_cube_mesh"),resources(),8,8,8,variant)) {
                        require(check.isValid(),"Original variant failed: "+variant);
                    }
                    require(before.equals(state()),"Shader variant load/close changed GL state");
                }
        try(var check=ComputeShader.loadShader(Identifier.parse("simpleclouds:cloud_regions"),resources(),16,16,1,
                ImmutableMap.of("EDGE_FADE_FACTOR","0.005"))) {
            require(check.isValid(),"Cloud region compute did not compile");
        }
        try (var shader=ComputeShader.loadShader(Identifier.parse("simpleclouds:original_cube_mesh"),resources(),8,8,8,options);
             var adapter=new OriginalMeshDrawAdapter(resources())) {
            require(shader.isValid(),"No live compute program");
            buffers(shader);
            require(before.equals(state()),"Allocation changed caller GL state");
            for(float offset:new float[]{10,-10,-.5f,10}) {
                for(String counter:List.of("TotalSides","SidesPerChunk","TotalTransparentCubes","TransparentCubesPerChunk"))
                    shader.getShaderStorageBuffer(counter).writeData(b->b.putInt(0),4,false);
                shader.getShaderStorageBuffer("NoiseLayers").writeData(b -> {
                    b.putFloat(4).putFloat(offset).putFloat(16).putFloat(16).putFloat(16)
                        .putFloat(1).putFloat(0).putFloat(0);
                },32,false);
                shader.dispatchAndWait(1,1,1);
                require(before.equals(state()),"Dispatch did not restore SSBO range/program/active texture");
                int[] counts=new int[2];
                shader.getShaderStorageBuffer("TotalSides").readData(b->counts[0]=b.getInt(0),4);
                shader.getShaderStorageBuffer("TotalTransparentCubes").readData(b->counts[1]=b.getInt(0),4);
                int expectedOpaque=offset==10?128:0;
                int expectedTransparent=offset==-.5f?256:0;
                require(counts[0]==expectedOpaque && counts[1]==expectedTransparent,
                    "Original fixture offset="+offset+" counts="+Arrays.toString(counts));
                if(counts[0]>0) shader.getShaderStorageBuffer("SideInfoBuffer").readData(b->{
                    for(int n=0;n<counts[0];n++) {
                        int base=n*24;
                        require(b.getInt(base)==2 || b.getInt(base)==3,"Original integer side ABI changed");
                        require(b.getFloat(base+16)==1 && b.getFloat(base+20)==.5f,"Original brightness/radius ABI changed");
                    }
                },counts[0]*24);
                for(boolean transparent:new boolean[]{false,true}) {
                    int count=counts[transparent?1:0];
                    int faces=count;
                    int stride=24;
                    int target=GL45.glCreateBuffers();
                    try {
                        GL45.glNamedBufferData(target,Math.max(4,(long)faces*stride),GL15.GL_DYNAMIC_DRAW);
                        int source=shader.getShaderStorageBuffer(transparent?"TransparentCubeInfoBuffer":"SideInfoBuffer").getId();
                        require(adapter.convert(source,count,target,transparent,8,128)==faces,"Wrong converted face count");
                        require(before.equals(state()),"Adapter changed external GL state");
                        if(faces>0) {
                            var data=java.nio.ByteBuffer.allocateDirect(faces*stride).order(java.nio.ByteOrder.nativeOrder());
                            GL45.glGetNamedBufferSubData(target,0,data);
                            for(int n=0;n<faces;n++) {
                                int base=n*stride;
                                float side=data.getFloat(base),y=data.getFloat(base+(transparent?4:8));
                                if(!transparent) require(side>=0 && side<6 && side==(int)side,"Invalid float draw side");
                                require(y>=132 && y<=156,"Wrong world cloud height");
                                require(data.getFloat(base+(transparent?12:16))==4 && data.getFloat(base+(transparent?16:20))==1,"Wrong draw radius/brightness");
                                if(transparent) require(data.getFloat(base+20)>0 && data.getFloat(base+20)<1,"Invalid alpha");
                            }
                        }
                    } finally {GL15.glDeleteBuffers(target);}
                }
                System.out.println("ORIGINAL fixture offset="+offset+" opaque="+counts[0]+" transparent="+counts[1]);
            }
            try {
                shader.getShaderStorageBuffer("TotalSides").readData(b->{throw new IllegalArgumentException("fixture");},4);
                throw new AssertionError("Callback failure swallowed");
            } catch(IllegalArgumentException expected) {}
            shader.getShaderStorageBuffer("TotalSides").readData(b->require(b.getInt(0)==128,"Map not recovered"),4);
            require(before.equals(state()),"Read/write mapping changed state");
        }
        int[] actual=new int[4];
        GL45.glGetNamedBufferSubData(sentinel,alignment,actual);
        require(Arrays.equals(actual,new int[]{12345,67890,123,456}),"Unrelated SSBO overwritten");
        require(before.equals(state()),"Cleanup changed external binding");
        var generator=CloudMeshGenerator.builder().lodConfig(new LevelOfDetailConfig(2))
            .meshGenInterval(2).useTransparency(true).createMultiRegion();
        generator.setCloudGetter(new dev.nonamecrackers2.simpleclouds.common.cloud.region.CloudGetter() {
            public dev.nonamecrackers2.simpleclouds.common.cloud.CloudType[] getIndexedCloudTypes() {
                return new dev.nonamecrackers2.simpleclouds.common.cloud.CloudType[0];
            }
            public dev.nonamecrackers2.simpleclouds.common.cloud.CloudType getCloudTypeForId(Identifier id) { return null; }
            public java.util.List<dev.nonamecrackers2.simpleclouds.common.cloud.region.CloudRegion> getClouds() { return List.of(); }
            public org.apache.commons.lang3.tuple.Pair<dev.nonamecrackers2.simpleclouds.common.cloud.CloudType,Float> getCloudTypeAtPosition(float x,float z) {
                return org.apache.commons.lang3.tuple.Pair.of(dev.nonamecrackers2.simpleclouds.common.cloud.SimpleCloudsConstants.EMPTY,0f);
            }
        });
        try {
            var result=generator.init(resources());
            for(var error:result.getErrors()) if(error.error()!=null) error.error().printStackTrace();
            require(result.getErrors().isEmpty(),"Original multi-region initialization errors: "+result.getErrors());
            require(GL11.glIsTexture(generator.getCloudRegionTextureId()),"Region texture is still a stub");
            require(before.equals(state()),"Whole generator initialization changed caller state");
            generator.setCloudGetter(dev.nonamecrackers2.simpleclouds.common.cloud.region.CloudGetter.EMPTY);
            for(int frame=0;frame<8;frame++) generator.genTick(0,0,0,null,1);
            require(before.equals(state()),"Whole generator tick changed caller state");
        } finally { generator.close(); }
        require(before.equals(state()),"Whole generator cleanup changed caller state");
		var params=com.google.common.collect.ImmutableMap.<dev.nonamecrackers2.simpleclouds.common.noise.AbstractNoiseSettings.Param,Float>builder();
		for(var parameter:dev.nonamecrackers2.simpleclouds.common.noise.AbstractNoiseSettings.Param.values())
			params.put(parameter,switch(parameter) {
				case HEIGHT -> 4f;
				case VALUE_OFFSET -> 10f;
				case VALUE_SCALE -> 0f;
				case FADE_DISTANCE -> 1f;
				case HEIGHT_OFFSET -> 0f;
				default -> 16f;
			});
		var solidType=new dev.nonamecrackers2.simpleclouds.common.cloud.CloudType(Identifier.parse("test:solid"),
			dev.nonamecrackers2.simpleclouds.api.common.cloud.weather.WeatherType.NONE,0,0,1,0,
			new dev.nonamecrackers2.simpleclouds.common.noise.StaticNoiseSettings(params.build()));
		var single=CloudMeshGenerator.builder().lodConfig(new LevelOfDetailConfig(2)).testFacesFacingAway(true)
			.useTransparency(true).createSingleRegion(solidType);
		try {
			var initialized=single.init(resources());
			require(initialized.getErrors().isEmpty(),"Original single-region init failed: "+initialized.getErrors());
			single.generateMesh();
			int[] total={0};
			single.forRenderableMeshChunks(null,dev.nonamecrackers2.simpleclouds.client.mesh.chunk.MeshChunk::getOpaqueBuffers,
				(chunk,buffers)->total[0]+=buffers.getElementCount());
			require(total[0]==8192,"Original full single-region mesh fixture count: "+total[0]);
			System.out.println("ORIGINAL single-region full mesh opaque="+total[0]);
			single.setCloudType(dev.nonamecrackers2.simpleclouds.common.cloud.SimpleCloudsConstants.EMPTY);
			single.generateMesh();
			total[0]=0;
			single.forRenderableMeshChunks(null,dev.nonamecrackers2.simpleclouds.client.mesh.chunk.MeshChunk::getOpaqueBuffers,
				(chunk,buffers)->total[0]+=buffers.getElementCount());
			require(total[0]==0,"Empty preview type retained previous mesh");
			System.out.println("ORIGINAL single-region after empty type opaque="+total[0]);
		} finally {single.close();}
		require(before.equals(state()),"Populated single-region generation changed caller GL state");
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,slot,0);
        GL42.glBindImageTexture(imageUnit,0,0,false,0,GL15.GL_READ_ONLY,GL30.GL_R32F);
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY,0);
        GL11.glDeleteTextures(sentinelTexture);
        GL15.glDeleteBuffers(sentinel);
        require(GL11.glGetError()==GL11.GL_NO_ERROR,"GL error after original compute test");
        System.out.println("PASS: whole original multi-region init/tick/close, eight original shader variants and region shader, original GPU dispatch and GPU-only opaque/transparent draw conversion, integer ABI, solid/empty/transparent regeneration, mapped-error cleanup, external SSBO/image/texture/VAO preservation");
    }
    static int compileStormStage(int type, String path) throws Exception {
        String source=Files.readString(Path.of(path));
        source=source.replace("#include <simpleclouds:cloud_faces.glsl>",
            Files.readString(Path.of("src/main/resources/assets/simpleclouds/shaders/include/cloud_faces.glsl")));
        int shader=GL20.glCreateShader(type);
        GL20.glShaderSource(shader,source); GL20.glCompileShader(shader);
        require(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)!=0,GL20.glGetShaderInfoLog(shader));
        return shader;
    }
    static void runStormShadow() throws Exception {
        String root="src/main/resources/assets/simpleclouds/shaders/core/original_storm_shadow";
        int vertex=compileStormStage(GL20.GL_VERTEX_SHADER,root+".vsh");
        int fragment=compileStormStage(GL20.GL_FRAGMENT_SHADER,root+".fsh");
        int program=GL20.glCreateProgram();
        GL20.glAttachShader(program,vertex); GL20.glAttachShader(program,fragment); GL20.glLinkProgram(program);
        require(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(program));
        int color=GL45.glCreateTextures(GL11.GL_TEXTURE_2D), depth=GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
        GL45.glTextureStorage2D(color,1,GL11.GL_RGBA8,16,16);
        GL45.glTextureStorage2D(depth,1,GL30.GL_DEPTH_COMPONENT32F,16,16);
        int fbo=GL45.glCreateFramebuffers();
        GL45.glNamedFramebufferTexture(fbo,GL30.GL_COLOR_ATTACHMENT0,color,0);
        GL45.glNamedFramebufferTexture(fbo,GL30.GL_DEPTH_ATTACHMENT,depth,0);
        require(GL45.glCheckNamedFramebufferStatus(fbo,GL30.GL_FRAMEBUFFER)==GL30.GL_FRAMEBUFFER_COMPLETE,"Storm FBO incomplete");
        int vao=GL30.glGenVertexArrays(), vertices=GL45.glCreateBuffers();
        int matrices=GL45.glCreateBuffers(), params=GL45.glCreateBuffers();
        try {
            GL30.glBindVertexArray(vao); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vertices);
            GL45.glNamedBufferData(vertices,new float[]{-1,-1,1, -1,-1,-1, -1,1,-1, -1,-1,1, -1,1,-1, -1,1,1},GL15.GL_STATIC_DRAW);
            GL20.glEnableVertexAttribArray(0); GL20.glVertexAttribPointer(0,3,GL11.GL_FLOAT,false,12,0);
            GL20.glVertexAttrib1f(1,4); GL20.glVertexAttrib3f(2,0,128,1); GL20.glVertexAttrib1f(3,1);
            java.nio.ByteBuffer data=java.nio.ByteBuffer.allocateDirect(128).order(java.nio.ByteOrder.nativeOrder());
            new org.joml.Matrix4f().translation(0,-128,0).get(0,data);
            new org.joml.Matrix4f().get(64,data);
            GL45.glNamedBufferData(matrices,data,GL15.GL_STATIC_DRAW);
            GL31.glUniformBlockBinding(program,GL31.glGetUniformBlockIndex(program,"ShadowMatrices"),0);
            GL31.glUniformBlockBinding(program,GL31.glGetUniformBlockIndex(program,"StormShadow"),1);
            GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER,0,matrices);
            GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER,1,params);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo); GL11.glViewport(0,0,16,16);
            GL20.glUseProgram(program); GL11.glDisable(GL11.GL_CULL_FACE); GL11.glDisable(GL11.GL_BLEND);
            GL11.glEnable(GL11.GL_DEPTH_TEST); GL11.glDepthFunc(GL11.GL_LESS); GL11.glDepthMask(true);
            GL11.glClearColor(0,0,0,0); GL11.glClearDepth(1);
            for(int fixture=0;fixture<3;fixture++) {
                GL45.glNamedBufferData(params,new float[]{fixture==2?-300:128,1f/8,0,0},GL15.GL_DYNAMIC_DRAW);
                GL20.glVertexAttrib1f(4,fixture==1?0.8f:0.4f);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT);
                GL11.glDrawArrays(GL11.GL_TRIANGLES,0,6);
                java.nio.ByteBuffer pixel=java.nio.ByteBuffer.allocateDirect(4);
                GL11.glReadPixels(8,8,1,1,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixel);
                require((pixel.get(3)&255)==(fixture==0?255:0),"Storm shadow alpha fixture "+fixture);
                if(fixture==0) require(Math.abs((pixel.get(0)&255)-102)<=1,"Storm brightness lost");
            }
            require(GL11.glGetError()==GL11.GL_NO_ERROR,"Storm shadow GL error");
            System.out.println("PASS Intel original storm shadow shader: dark geometry writes brightness, bright/high geometry discarded");
        } finally {
            GL20.glUseProgram(0); GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,0); GL30.glBindVertexArray(0);
            GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER,0,0); GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER,1,0);
            GL20.glDeleteProgram(program); GL20.glDeleteShader(vertex); GL20.glDeleteShader(fragment);
            GL30.glDeleteVertexArrays(vao); GL15.glDeleteBuffers(vertices); GL15.glDeleteBuffers(matrices); GL15.glDeleteBuffers(params);
            GL30.glDeleteFramebuffers(fbo); GL11.glDeleteTextures(color); GL11.glDeleteTextures(depth);
        }
    }
    static void runStormFog() throws Exception {
        String root="src/main/resources/assets/simpleclouds/shaders/core/original_storm_fog";
        int vertex=compileStormStage(GL20.GL_VERTEX_SHADER,root+".vsh");
        int fragment=compileStormStage(GL20.GL_FRAGMENT_SHADER,root+".fsh");
        int program=GL20.glCreateProgram();
        GL20.glAttachShader(program,vertex); GL20.glAttachShader(program,fragment); GL20.glLinkProgram(program);
        require(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(program));
        int color=GL45.glCreateTextures(GL11.GL_TEXTURE_2D), scene=GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
        int shadowDepth=GL45.glCreateTextures(GL11.GL_TEXTURE_2D), shadowColor=GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
        GL45.glTextureStorage2D(color,1,GL11.GL_RGBA8,16,16);
        for(int texture:new int[]{scene,shadowDepth}) GL45.glTextureStorage2D(texture,1,GL30.GL_R32F,16,16);
        GL45.glTextureStorage2D(shadowColor,1,GL11.GL_RGBA8,16,16);
        for(int texture:new int[]{scene,shadowDepth,shadowColor}) {
            GL45.glTextureParameteri(texture,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
            GL45.glTextureParameteri(texture,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
            GL45.glTextureParameteri(texture,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);
            GL45.glTextureParameteri(texture,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
        }
        int fbo=GL45.glCreateFramebuffers(), vao=GL30.glGenVertexArrays();
        int vertices=GL45.glCreateBuffers(), params=GL45.glCreateBuffers();
        GL45.glNamedFramebufferTexture(fbo,GL30.GL_COLOR_ATTACHMENT0,color,0);
        require(GL45.glCheckNamedFramebufferStatus(fbo,GL30.GL_FRAMEBUFFER)==GL30.GL_FRAMEBUFFER_COMPLETE,"Fog FBO incomplete");
        try {
            GL30.glBindVertexArray(vao); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vertices);
            GL45.glNamedBufferData(vertices,new float[]{-1,-1,3,-1,-1,3},GL15.GL_STATIC_DRAW);
            GL20.glEnableVertexAttribArray(0); GL20.glVertexAttribPointer(0,2,GL11.GL_FLOAT,false,8,0);
            GL20.glUseProgram(program);
            GL31.glUniformBlockBinding(program,GL31.glGetUniformBlockIndex(program,"OriginalStormFog"),0);
            GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER,0,params);
            String[] names={"DepthSampler","ShadowMap","ShadowMapColor"};
            int[] textures={scene,shadowDepth,shadowColor};
            for(int i=0;i<3;i++) { GL41.glProgramUniform1i(program,GL20.glGetUniformLocation(program,names[i]),i); GL45.glBindTextureUnit(i,textures[i]); }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo); GL11.glViewport(0,0,16,16);
            GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glDisable(GL11.GL_BLEND); GL11.glDisable(GL11.GL_CULL_FACE);
            java.nio.ByteBuffer data=java.nio.ByteBuffer.allocateDirect(560).order(java.nio.ByteOrder.nativeOrder());
            var shadowProjection=new org.joml.Matrix4f().scaling(0); // constant center coordinate: controlled covered volume
            int baseline=-1;
            for(int fixture=0;fixture<7;fixture++) {
                float cameraY=fixture==6?-10000:0;
                var worldView=new org.joml.Matrix4f().translation(0,-cameraY,0);
                float[] bolts=fixture==4?new float[]{0,0,0,0.5f}:fixture==5?new float[]{10000,0,0,0.5f}:new float[0];
                dev.nonamecrackers2.simpleclouds.client.renderer.v2.OriginalStormFogUniforms.write(data,
                    new org.joml.Matrix4f(),worldView,shadowProjection,new org.joml.Matrix4f(),
                    0,cameraY,0,4000,new float[]{1,1,1},bolts,bolts.length/4,0);
                GL45.glNamedBufferData(params,data,GL15.GL_DYNAMIC_DRAW);
                GL44.glClearTexImage(scene,0,GL11.GL_RED,GL11.GL_FLOAT,new float[]{fixture==2?0.00001f:0});
                GL44.glClearTexImage(shadowDepth,0,GL11.GL_RED,GL11.GL_FLOAT,new float[]{fixture==1?1:0});
                float brightness=fixture==3?0.8f:0.4f;
                GL44.glClearTexImage(shadowColor,0,GL11.GL_RGBA,GL11.GL_FLOAT,new float[]{brightness,brightness,brightness,1});
                GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
                java.nio.ByteBuffer pixel=java.nio.ByteBuffer.allocateDirect(4);
                GL11.glReadPixels(8,8,1,1,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixel);
                int red=pixel.get(0)&255, alpha=pixel.get(3)&255;
                if(fixture==0) { require(alpha==255,"Covered sky did not reach original density"); require(Math.abs(red-61)<=1,"Original color multiplier"); baseline=red; }
                if(fixture==1 || fixture==2 || fixture==3 || fixture==6) require(alpha==0,"Unexpected fog in fixture "+fixture+" alpha="+alpha);
                if(fixture==4) require(red>baseline,"Nearby original lightning did not brighten fog");
                if(fixture==5) require(red==baseline,"Far lightning changed fog");
            }
            require(GL11.glGetError()==GL11.GL_NO_ERROR,"Storm fog GL error");
            System.out.println("PASS Intel original fog raymarch: covered/clear shadow, terrain cutoff, bright exclusion, near/far lightning, vertical fade");
        } finally {
            GL20.glUseProgram(0); GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,0); GL30.glBindVertexArray(0);
            GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER,0,0);
            for(int i=0;i<3;i++) GL45.glBindTextureUnit(i,0);
            GL20.glDeleteProgram(program); GL20.glDeleteShader(vertex); GL20.glDeleteShader(fragment);
            GL30.glDeleteVertexArrays(vao); GL15.glDeleteBuffers(vertices); GL15.glDeleteBuffers(params);
            GL30.glDeleteFramebuffers(fbo);
            for(int texture:new int[]{color,scene,shadowDepth,shadowColor}) GL11.glDeleteTextures(texture);
        }
    }
    static float sampleLinear(float[] image,int width,int height,float x,float y,int channel) {
        int x0=(int)Math.floor(x), y0=(int)Math.floor(y);
        float fx=x-x0, fy=y-y0;
        int aX=Math.max(0,Math.min(width-1,x0)), bX=Math.max(0,Math.min(width-1,x0+1));
        int aY=Math.max(0,Math.min(height-1,y0)), bY=Math.max(0,Math.min(height-1,y0+1));
        float a=image[(aY*width+aX)*4+channel], b=image[(aY*width+bX)*4+channel];
        float c=image[(bY*width+aX)*4+channel], d=image[(bY*width+bX)*4+channel];
        return (a+(b-a)*fx)*(1-fy)+(c+(d-c)*fx)*fy;
    }
    static float[] referenceOriginalBlur(float[] image,int width,int height,boolean horizontal) {
        float[] out=new float[image.length];
        for(int y=0;y<height;y++) for(int x=0;x<width;x++) for(int c=0;c<4;c++) {
            float sum=0;
            for(float a=-9.5f;a<=10;a+=2)
                sum+=sampleLinear(image,width,height,x+(horizontal?a:0),y+(horizontal?0:a),c);
            sum+=sampleLinear(image,width,height,x+(horizontal?10:0),y+(horizontal?0:10),c)*0.5f;
            out[(y*width+x)*4+c]=Math.round(sum/10.5f*255)/255f;
        }
        return out;
    }
    static void runStormBlur() throws Exception {
        String root="src/main/resources/assets/simpleclouds/shaders/core/";
        int vertex=compileStormStage(GL20.GL_VERTEX_SHADER,root+"original_storm_fog.vsh");
        int fragment=compileStormStage(GL20.GL_FRAGMENT_SHADER,root+"original_storm_blur.fsh");
        int program=GL20.glCreateProgram();
        GL20.glAttachShader(program,vertex); GL20.glAttachShader(program,fragment); GL20.glLinkProgram(program);
        require(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(program));
        int[] textures={GL45.glCreateTextures(GL11.GL_TEXTURE_2D),GL45.glCreateTextures(GL11.GL_TEXTURE_2D)};
        for(int texture:textures) {
            GL45.glTextureStorage2D(texture,1,GL11.GL_RGBA8,32,32);
            GL45.glTextureParameteri(texture,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_LINEAR);
            GL45.glTextureParameteri(texture,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_LINEAR);
            GL45.glTextureParameteri(texture,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);
            GL45.glTextureParameteri(texture,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
        }
        int fbo=GL45.glCreateFramebuffers(),vao=GL30.glGenVertexArrays();
        int vertices=GL45.glCreateBuffers(),params=GL45.glCreateBuffers();
        try {
            GL30.glBindVertexArray(vao); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vertices);
            GL45.glNamedBufferData(vertices,new float[]{-1,-1,3,-1,-1,3},GL15.GL_STATIC_DRAW);
            GL20.glEnableVertexAttribArray(0); GL20.glVertexAttribPointer(0,2,GL11.GL_FLOAT,false,8,0);
            GL20.glUseProgram(program);
            GL31.glUniformBlockBinding(program,GL31.glGetUniformBlockIndex(program,"OriginalBlur"),0);
            GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER,0,params);
            GL41.glProgramUniform1i(program,GL20.glGetUniformLocation(program,"DiffuseSampler"),0);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo); GL11.glViewport(0,0,32,32);
            GL11.glDisable(GL11.GL_BLEND); GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glDisable(GL11.GL_CULL_FACE);
            for(int fixture=0;fixture<2;fixture++) {
                float[] reference=new float[32*32*4];
                for(int y=0;y<32;y++) for(int x=0;x<32;x++) for(int c=0;c<4;c++) {
                    float value=fixture==0?new float[]{51,102,153,77}[c]/255f:
                        (x>=12&&x<20&&y>=12&&y<20?(c==3?1:0.6f):0);
                    reference[(y*32+x)*4+c]=value;
                }
                GL45.glTextureSubImage2D(textures[0],0,0,0,32,32,GL11.GL_RGBA,GL11.GL_FLOAT,reference);
                for(int pass=0;pass<6;pass++) {
                    int source=pass%2, target=1-source;
                    GL45.glNamedFramebufferTexture(fbo,GL30.GL_COLOR_ATTACHMENT0,textures[target],0);
                    require(GL45.glCheckNamedFramebufferStatus(fbo,GL30.GL_FRAMEBUFFER)==GL30.GL_FRAMEBUFFER_COMPLETE,"Blur FBO incomplete");
                    GL45.glBindTextureUnit(0,textures[source]);
                    GL45.glNamedBufferData(params,new float[]{pass%2==0?1f/32:0,pass%2==1?1f/32:0,10,0},GL15.GL_DYNAMIC_DRAW);
                    GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
                    reference=referenceOriginalBlur(reference,32,32,pass%2==0);
                    java.nio.ByteBuffer pixels=java.nio.ByteBuffer.allocateDirect(reference.length);
                    GL11.glReadPixels(0,0,32,32,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
                    for(int i=0;i<reference.length;i++) require(Math.abs((pixels.get(i)&255)-Math.round(reference[i]*255))<=2,
                        "Original blur mismatch fixture="+fixture+" pass="+pass+" byte="+i);
                }
            }
            require(GL11.glGetError()==GL11.GL_NO_ERROR,"Storm blur GL error");
            System.out.println("PASS Intel six-pass original box blur vs independent bilinear/quantized reference, all RGBA channels and constant/impulse fixtures");
        } finally {
            GL20.glUseProgram(0); GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,0); GL30.glBindVertexArray(0);
            GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER,0,0); GL45.glBindTextureUnit(0,0);
            GL20.glDeleteProgram(program); GL20.glDeleteShader(vertex); GL20.glDeleteShader(fragment);
            GL30.glDeleteVertexArrays(vao); GL15.glDeleteBuffers(vertices); GL15.glDeleteBuffers(params);
            GL30.glDeleteFramebuffers(fbo); for(int texture:textures) GL11.glDeleteTextures(texture);
        }
    }
    static class MutableCloudGetter implements dev.nonamecrackers2.simpleclouds.common.cloud.region.CloudGetter {
        dev.nonamecrackers2.simpleclouds.common.cloud.CloudType[] types;
        List<dev.nonamecrackers2.simpleclouds.common.cloud.region.CloudRegion> regions=List.of();
        public dev.nonamecrackers2.simpleclouds.common.cloud.CloudType[] getIndexedCloudTypes() { return types; }
        public dev.nonamecrackers2.simpleclouds.common.cloud.CloudType getCloudTypeForId(Identifier id) {
            for(var type:types) if(type.id().equals(id)) return type;
            return null;
        }
        public List<dev.nonamecrackers2.simpleclouds.common.cloud.region.CloudRegion> getClouds() { return regions; }
        public org.apache.commons.lang3.tuple.Pair<dev.nonamecrackers2.simpleclouds.common.cloud.CloudType,Float> getCloudTypeAtPosition(float x,float z) {
            var result=dev.nonamecrackers2.simpleclouds.common.cloud.region.CloudRegion.calculateAt(regions,x,z);
            var type=result.getLeft()==null?null:getCloudTypeForId(result.getLeft().getCloudTypeId());
            return org.apache.commons.lang3.tuple.Pair.of(type==null?dev.nonamecrackers2.simpleclouds.common.cloud.SimpleCloudsConstants.EMPTY:type,1-result.getRight());
        }
    }
    static dev.nonamecrackers2.simpleclouds.common.cloud.CloudType fixtureType(String id,float offset) {
        var builder=ImmutableMap.<dev.nonamecrackers2.simpleclouds.common.noise.AbstractNoiseSettings.Param,Float>builder();
        for(var param:dev.nonamecrackers2.simpleclouds.common.noise.AbstractNoiseSettings.Param.values())
            builder.put(param,switch(param) {
                case HEIGHT -> 4f; case VALUE_OFFSET -> offset; case VALUE_SCALE -> 0f;
                case FADE_DISTANCE -> 1f; case HEIGHT_OFFSET -> 0f; default -> 16f;
            });
        return new dev.nonamecrackers2.simpleclouds.common.cloud.CloudType(Identifier.parse(id),
            dev.nonamecrackers2.simpleclouds.api.common.cloud.weather.WeatherType.NONE,0,0,1,0,
            new dev.nonamecrackers2.simpleclouds.common.noise.StaticNoiseSettings(builder.build()));
    }
    static dev.nonamecrackers2.simpleclouds.common.cloud.region.CloudRegion fixtureRegion(String id,float x,float radius) {
        var region=new dev.nonamecrackers2.simpleclouds.common.cloud.region.CloudRegion(Identifier.parse(id),
            new net.minecraft.world.phys.Vec2(0,0),0,0,x,0,radius,0,1,10000,0,0);
        region.setRadius(radius);
        return region;
    }
    static int opaqueCount(CloudMeshGenerator generator) {
        int[] total={0};
        generator.forRenderableMeshChunks(null,dev.nonamecrackers2.simpleclouds.client.mesh.chunk.MeshChunk::getOpaqueBuffers,
            (chunk,buffers)->total[0]+=buffers.getElementCount());
        return total[0];
    }
    static float[] regionPixels(int texture) {
        int size=GL45.glGetTextureLevelParameteri(texture,0,GL11.GL_TEXTURE_WIDTH);
        int height=GL45.glGetTextureLevelParameteri(texture,0,GL11.GL_TEXTURE_HEIGHT);
        int layers=GL45.glGetTextureLevelParameteri(texture,0,GL12.GL_TEXTURE_DEPTH);
        float[] out=new float[size*height*layers*2];
        GL45.glGetTextureImage(texture,0,GL30.GL_RG,GL11.GL_FLOAT,out);
        return out;
    }
    static void runPopulatedMultiRegion() throws Exception {
        var getter=new MutableCloudGetter();
        var solid=fixtureType("test:multi_solid",10);
        var other=fixtureType("test:multi_other",10);
        getter.types=new dev.nonamecrackers2.simpleclouds.common.cloud.CloudType[]{
            dev.nonamecrackers2.simpleclouds.common.cloud.SimpleCloudsConstants.EMPTY,solid,other};
        getter.regions=List.of(fixtureRegion("test:multi_solid",0,1000));
        var before=state();
        var generator=CloudMeshGenerator.builder().lodConfig(new LevelOfDetailConfig(2)).meshGenInterval(2)
            .testFacesFacingAway(true).useTransparency(true).createMultiRegion();
        generator.setCloudGetter(getter);
        try {
            var result=generator.init(resources());
            require(result.getErrors().isEmpty(),"Populated multi-region init failed: "+result.getErrors());
            generator.generateMesh();
            require(opaqueCount(generator)==8192,"Populated multi-region solid count: "+opaqueCount(generator));
            for(int reload=0;reload<3;reload++) {
                // Two-frame generation ends with a pending snapshot fence in the
                // experimental path. Reload must release it without publishing stale data.
                generator.genTick(0,0,0,null,1);
                generator.genTick(0,0,0,null,1);
                var reloaded=generator.init(resources());
                require(reloaded.getErrors().isEmpty(),"Pending-generation reload failed: "+reloaded.getErrors());
                generator.generateMesh();
                require(opaqueCount(generator)==8192,"Reload retained stale or missing counter data");
                require(before.equals(state()),"Pending-generation reload changed caller state");
                require(GL11.glGetError()==GL11.GL_NO_ERROR,"Pending-generation reload GL error");
            }
            System.out.println("PASS Intel original generator three reloads with pending generation, immediate preview regeneration and exact 8192 face count");
            float[] map=regionPixels(generator.getCloudRegionTextureId());
            for(int i=0;i<map.length;i+=2) require(map[i]==1 && map[i+1]==1,"Solid region map index/fade at "+i);
            require(before.equals(state()),"Populated multi-region generation changed caller GL state");
            // Mutate types on the SAME getter and SAME array to exercise observedTypes snapshot invalidation.
            getter.types[1]=fixtureType("test:multi_solid",-10);
            generator.setCloudGetter(getter); generator.generateMesh();
            require(opaqueCount(generator)==0,"Type replacement retained solid geometry");
            getter.types[1]=solid;
            generator.setCloudGetter(getter); generator.generateMesh();
            require(opaqueCount(generator)==8192,"Restored type did not rebuild original geometry");
            getter.regions=List.of(fixtureRegion("test:multi_solid",10000,1000));
            generator.generateMesh();
            require(opaqueCount(generator)==0,"Moved-away region retained geometry");
            map=regionPixels(generator.getCloudRegionTextureId());
            for(float value:map) require(value==0,"Moved-away region left stale map pixels");
            getter.regions=List.of(fixtureRegion("test:multi_solid",0,1000),fixtureRegion("test:multi_other",0,16));
            generator.generateMesh();
            map=regionPixels(generator.getCloudRegionTextureId());
            int size=GL45.glGetTextureLevelParameteri(generator.getCloudRegionTextureId(),0,GL11.GL_TEXTURE_WIDTH);
            int center=(size/2*size+size/2)*2;
            require(map[center]==2 && Math.abs(map[center+1]-0.08f)<0.00001f,"Overlapping type index/inner fade wrong");
            // Original composite keeps the outer type but multiplies its fade by the other type's halo.
            float haloFade=Math.min(((float)Math.hypot(size/2,size/2)-16)*0.005f,1);
            require(map[0]==1 && Math.abs(map[1]-haloFade)<0.00001f,"Different-type outer halo fade is not original");
            require(opaqueCount(generator)>0,"Overlapping populated regions produced no mesh");
            getter.regions=List.of(); generator.generateMesh();
            require(opaqueCount(generator)==0,"Empty regions retained geometry");
            getter.regions=List.of(fixtureRegion("test:multi_solid",0,1000));
            for(int frame=0;frame<7;frame++) generator.genTick(0,0,0,null,1);
            require(opaqueCount(generator)==8192,"Original two-frame cadence failed to publish solid mesh");
            require(generator.getCompletedGenerationCycles()>=2,"Original cadence completion not observed");
            require(before.equals(state()),"Populated multi-region type/region/cadence changes altered caller GL state");
            require(GL11.glGetError()==GL11.GL_NO_ERROR,"Populated multi-region GL error");
            System.out.println("PASS Intel populated original multi-region: 8192 solid faces, same-getter type invalidation, moved/empty clearing, overlap index/fade, original two-frame cadence, caller state");
        } finally {generator.close();}
        require(before.equals(state()),"Populated multi-region cleanup changed caller GL state");
    }
    static void runRegionLodLayers() throws Exception {
        var getter=new MutableCloudGetter();
        getter.types=new dev.nonamecrackers2.simpleclouds.common.cloud.CloudType[]{
            dev.nonamecrackers2.simpleclouds.common.cloud.SimpleCloudsConstants.EMPTY,fixtureType("test:lod_region",10)};
        var region=new dev.nonamecrackers2.simpleclouds.common.cloud.region.CloudRegion(Identifier.parse("test:lod_region"),
            new net.minecraft.world.phys.Vec2(0,0),0,0,31,-19,60,0.75f,1.4f,10000,0,0);
        region.setRadius(60); getter.regions=List.of(region);
        var lod=new LevelOfDetailConfig(2,new dev.nonamecrackers2.simpleclouds.client.mesh.lod.LevelOfDetail(2,1),
            new dev.nonamecrackers2.simpleclouds.client.mesh.lod.LevelOfDetail(4,1));
        var generator=CloudMeshGenerator.builder().lodConfig(lod).testFacesFacingAway(true).useTransparency(true).createMultiRegion();
        generator.setCloudGetter(getter);
        var before=state();
        try {
            var result=generator.init(resources());
            require(result.getErrors().isEmpty(),"Three-layer region init failed: "+result.getErrors());
            generator.generateMesh();
            int texture=generator.getCloudRegionTextureId();
            int size=GL45.glGetTextureLevelParameteri(texture,0,GL11.GL_TEXTURE_WIDTH);
            int layers=GL45.glGetTextureLevelParameteri(texture,0,GL12.GL_TEXTURE_DEPTH);
            require(layers==3,"Original region array omitted LOD layers");
            float[] pixels=regionPixels(texture);
            var transform=region.createTransform(1);
            int checks=0;
            for(int layer=0;layer<layers;layer++) for(int z=0;z<size;z++) for(int x=0;x<size;x++) {
                float scale=1<<layer;
                var point=new org.joml.Vector2f((x-size/2)*scale,(z-size/2)*scale)
                    .sub(31,-19).mul(transform).add(31,-19);
                float distance=point.distance(31,-19);
                float index=distance<60?1:0, fade=distance<60?Math.min((60-distance)*0.005f,1):0;
                int offset=((layer*size+z)*size+x)*2;
                require(pixels[offset]==index && Math.abs(pixels[offset+1]-fade)<0.00001f,
                    "Rotated/stretch region LOD pixel mismatch layer="+layer+" x="+x+" z="+z);
                checks++;
            }
            require(opaqueCount(generator)>0,"Populated rotated three-LOD region produced no geometry");
            getter.regions=List.of(); generator.generateMesh();
            require(opaqueCount(generator)==0,"Three-LOD empty region left geometry");
            for(float value:regionPixels(texture)) require(value==0,"Stale LOD region texel");
            require(before.equals(state()),"Three-LOD generation changed caller GL state");
            require(GL11.glGetError()==GL11.GL_NO_ERROR,"Three-LOD GL error");
            System.out.println("PASS Intel original three-LOD rotated/stretch region map: "+checks+" texel comparisons, populated/empty geometry and state");
        } finally {generator.close();}
        require(before.equals(state()),"Three-LOD cleanup changed caller GL state");
    }
    static int compileSource(int type, String source) {
        int shader=GL20.glCreateShader(type);
        GL20.glShaderSource(shader,source); GL20.glCompileShader(shader);
        require(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)!=0,GL20.glGetShaderInfoLog(shader));
        return shader;
    }
    static int link(int vertex,int fragment) {
        int program=GL20.glCreateProgram();
        GL20.glAttachShader(program,vertex); GL20.glAttachShader(program,fragment); GL20.glLinkProgram(program);
        require(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(program));
        return program;
    }
    static void runTransparency() throws Exception {
        String root="src/main/resources/assets/simpleclouds/shaders/core/";
        // Exercise the actual fragment sources. Only the Minecraft-provided
        // transform block is replaced by its referenced uniform for this tiny GL fixture.
        String fragmentSource=Files.readString(Path.of(root+"clouds_transparency.fsh"))
            .replace("#include <minecraft:dynamictransforms.glsl>","uniform vec4 ColorModulator;");
        int vertex=compileSource(GL20.GL_VERTEX_SHADER,"""
            #version 330
            #extension GL_ARB_separate_shader_objects : require
            uniform vec4 LayerColor;
            layout(location=0) out vec4 vertexColor;
            layout(location=1) out float fogDistance;
            layout(location=2) out float vertexDistance;
            void main() {
                vec2 p=vec2((gl_VertexID==1)?3.0:-1.0,(gl_VertexID==2)?3.0:-1.0);
                gl_Position=vec4(p,0,1); vertexColor=LayerColor; fogDistance=100; vertexDistance=100;
            }
            """);
        int fragment=compileSource(GL20.GL_FRAGMENT_SHADER,fragmentSource);
        int program=link(vertex,fragment);
        int accumFragment=compileSource(GL20.GL_FRAGMENT_SHADER,fragmentSource.replace("#version 330","#version 330\n#define ORIGINAL_ACCUM_ONLY"));
        int revealFragment=compileSource(GL20.GL_FRAGMENT_SHADER,fragmentSource.replace("#version 330","#version 330\n#define ORIGINAL_REVEALAGE_ONLY"));
        int accumProgram=link(vertex,accumFragment),revealProgram=link(vertex,revealFragment);
        int compositeFragment=compileStormStage(GL20.GL_FRAGMENT_SHADER,root+"original_transparency_composite.fsh");
        int compositeProgram=link(vertex,compositeFragment);
        int previewFragment=compileStormStage(GL20.GL_FRAGMENT_SHADER,root+"original_preview_composite.fsh");
        int previewProgram=link(vertex,previewFragment);
        int opaque=GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
        GL45.glTextureStorage2D(opaque,1,GL11.GL_RGBA8,1,1);
        int accum=GL45.glCreateTextures(GL11.GL_TEXTURE_2D),reveal=GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
        int result=GL45.glCreateTextures(GL11.GL_TEXTURE_2D),bayer=GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
        GL45.glTextureStorage2D(accum,1,GL30.GL_RGBA16F,1,1);
        GL45.glTextureStorage2D(reveal,1,GL30.GL_R8,1,1);
        GL45.glTextureStorage2D(result,1,GL11.GL_RGBA8,1,1);
        GL45.glTextureStorage2D(bayer,1,GL30.GL_R8,1,1);
        GL45.glClearTexImage(bayer,0,GL11.GL_RED,GL11.GL_FLOAT,new float[]{0});
        for(int texture:new int[]{accum,reveal,result,bayer}) {
            GL45.glTextureParameteri(texture,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
            GL45.glTextureParameteri(texture,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
        }
        int fbo=GL45.glCreateFramebuffers(),singleFbo=GL45.glCreateFramebuffers(),resultFbo=GL45.glCreateFramebuffers(),vao=GL30.glGenVertexArrays();
        GL45.glNamedFramebufferTexture(fbo,GL30.GL_COLOR_ATTACHMENT0,accum,0);
        GL45.glNamedFramebufferTexture(fbo,GL30.GL_COLOR_ATTACHMENT1,reveal,0);
        GL45.glNamedFramebufferDrawBuffers(fbo,new int[]{GL30.GL_COLOR_ATTACHMENT0,GL30.GL_COLOR_ATTACHMENT1});
        GL45.glNamedFramebufferTexture(resultFbo,GL30.GL_COLOR_ATTACHMENT0,result,0);
        require(GL45.glCheckNamedFramebufferStatus(fbo,GL30.GL_FRAMEBUFFER)==GL30.GL_FRAMEBUFFER_COMPLETE,"OIT FBO incomplete");
        int fog=GL45.glCreateBuffers();
        var fogBytes=java.nio.ByteBuffer.allocateDirect(32).order(java.nio.ByteOrder.nativeOrder());
        fogBytes.putFloat(16,10000).putFloat(20,20000).putFloat(24,1);
        GL45.glNamedBufferData(fog,fogBytes,GL15.GL_STATIC_DRAW);
        for(int p:new int[]{program,accumProgram,revealProgram}) {
            GL31.glUniformBlockBinding(p,GL31.glGetUniformBlockIndex(p,"CloudFog"),0);
            GL41.glProgramUniform4f(p,GL20.glGetUniformLocation(p,"ColorModulator"),1,1,1,1);
            GL41.glProgramUniform1i(p,GL20.glGetUniformLocation(p,"BayerMatrixSampler"),0);
        }
        GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER,0,fog);
        GL41.glProgramUniform1i(compositeProgram,GL20.glGetUniformLocation(compositeProgram,"AccumTexture"),0);
        GL41.glProgramUniform1i(compositeProgram,GL20.glGetUniformLocation(compositeProgram,"RevealageTexture"),1);
        GL41.glProgramUniform1i(previewProgram,GL20.glGetUniformLocation(previewProgram,"AccumTexture"),0);
        GL41.glProgramUniform1i(previewProgram,GL20.glGetUniformLocation(previewProgram,"RevealageTexture"),1);
        GL41.glProgramUniform1i(previewProgram,GL20.glGetUniformLocation(previewProgram,"CloudsTexture"),2);
        float[][] colors={{1,0,0,.25f},{0,0,1,.5f}};
        float[] first=null;
        try {
            GL30.glBindVertexArray(vao); GL11.glViewport(0,0,1,1);
            GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glDisable(GL11.GL_CULL_FACE);
            for(int order=0;order<5;order++) {
                GL45.glClearNamedFramebufferfv(fbo,GL11.GL_COLOR,0,new float[]{0,0,0,0});
                GL45.glClearNamedFramebufferfv(fbo,GL11.GL_COLOR,1,new float[]{1,0,0,0});
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);
                GL20.glUseProgram(program); GL45.glBindTextureUnit(0,bayer);
                GL11.glEnable(GL11.GL_BLEND);
                GL40.glBlendEquationi(0,GL14.GL_FUNC_ADD); GL40.glBlendEquationi(1,GL14.GL_FUNC_ADD);
                GL40.glBlendFunci(0,GL11.GL_ONE,GL11.GL_ONE);
                GL40.glBlendFunci(1,GL11.GL_ZERO,GL11.GL_ONE_MINUS_SRC_COLOR);
                if(order<2) for(int n=0;n<2;n++) {
                    float[] c=colors[order==0?n:1-n];
                    GL41.glProgramUniform4f(program,GL20.glGetUniformLocation(program,"LayerColor"),c[0],c[1],c[2],c[3]);
                    GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
                }
                if(order>=3) for(int n=0;n<2;n++) {
                    float[] c=colors[order==3?n:1-n];
                    for(int attachment=0;attachment<2;attachment++) {
                        int p=attachment==0?accumProgram:revealProgram;
                        GL45.glNamedFramebufferTexture(singleFbo,GL30.GL_COLOR_ATTACHMENT0,attachment==0?accum:reveal,0);
                        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,singleFbo);
                        GL20.glUseProgram(p);
                        GL40.glBlendFunci(0,attachment==0?GL11.GL_ONE:GL11.GL_ZERO,
                            attachment==0?GL11.GL_ONE:GL11.GL_ONE_MINUS_SRC_COLOR);
                        GL41.glProgramUniform4f(p,GL20.glGetUniformLocation(p,"LayerColor"),c[0],c[1],c[2],c[3]);
                        GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
                    }
                }
                // LWJGL needs a direct readback buffer.
                var read=java.nio.ByteBuffer.allocateDirect(16).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
                GL45.glGetTextureImage(accum,0,GL11.GL_RGBA,GL11.GL_FLOAT,read);
                float w0=.25f*3000*.729f,w1=.5f*3000*.729f;
                if(order!=2) {
                    require(Math.abs(read.get(0)-.25f*w0)<1 && Math.abs(read.get(2)-.5f*w1)<1
                        && Math.abs(read.get(3)-(.25f*w0+.5f*w1))<1,"Original weighted accumulation mismatch");
                }
                GL45.glClearNamedFramebufferfv(resultFbo,GL11.GL_COLOR,0,new float[]{.2f,.3f,.4f,.6f});
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,resultFbo);
                GL20.glUseProgram(compositeProgram); GL45.glBindTextureUnit(0,accum); GL45.glBindTextureUnit(1,reveal);
                GL40.glBlendFuncSeparatei(0,GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ZERO,GL11.GL_ONE);
                GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
                GL45.glGetTextureImage(result,0,GL11.GL_RGBA,GL11.GL_FLOAT,read);
                float[] pixel={read.get(0),read.get(1),read.get(2),read.get(3)};
                float r=order==2?1:.375f;
                float[] expected=order==2?new float[]{.2f,.3f,.4f,.6f}:new float[]{.2f*(1-r)+.2f*r,.3f*r,.8f*(1-r)+.4f*r,.6f};
                for(int c=0;c<4;c++) require(Math.abs(pixel[c]-expected[c])<2f/255,"OIT resolve mismatch: "+Arrays.toString(pixel));
                if(order==0) first=pixel;
                if(order==1) for(int c=0;c<4;c++) require(Math.abs(pixel[c]-first[c])<=1f/255,"Order-dependent transparency");
                if(order>=3) for(int c=0;c<4;c++) require(Math.abs(pixel[c]-first[c])<=1f/255,"Two-pass OIT differs from original MRT");
                for(float opacity:new float[]{0,128f/255,1}) {
                    GL45.glClearTexImage(opaque,0,GL11.GL_RGBA,GL11.GL_FLOAT,new float[]{.6f,.2f,.8f,opacity});
                    GL45.glClearNamedFramebufferfv(resultFbo,GL11.GL_COLOR,0,new float[]{.2f,.3f,.4f,.6f});
                    GL20.glUseProgram(previewProgram); GL45.glBindTextureUnit(2,opaque);
                    GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
                    GL45.glGetTextureImage(result,0,GL11.GL_RGBA,GL11.GL_FLOAT,read);
                    float[] background={.2f,.3f,.4f},opaqueColor={.6f,.2f,.8f},average={.2f,0,.8f};
                    for(int c=0;c<3;c++) {
                        float original=(opaqueColor[c]*opacity+background[c]*(1-opacity))*r+average[c]*(1-r);
                        require(Math.abs(read.get(c)-original)<3f/255,"Original preview composite mismatch, opacity="+opacity+", order="+order);
                    }
                    require(Math.abs(read.get(3)-.6f)<1f/255,"Preview modified background alpha");
                }
            }
            require(GL11.glGetError()==GL11.GL_NO_ERROR,"OIT GL error");
            System.out.println("PASS Intel original weighted OIT: RGBA16F/R8, both orders, two-pass variants vs original MRT, resolve, empty frame, scene alpha preservation");
            System.out.println("PASS Intel preview actual composite shader: 15 original-equation cases, transparent-only edges, opaque/partial/empty coverage and preserved background alpha");
        } finally {
            GL11.glDisable(GL11.GL_BLEND); GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,0); GL20.glUseProgram(0);
            GL30.glBindVertexArray(0); GL30.glDeleteVertexArrays(vao);
            GL30.glDeleteFramebuffers(fbo); GL30.glDeleteFramebuffers(singleFbo); GL30.glDeleteFramebuffers(resultFbo); GL15.glDeleteBuffers(fog);
            for(int texture:new int[]{accum,reveal,result,bayer,opaque}) GL11.glDeleteTextures(texture);
            GL20.glDeleteProgram(previewProgram); GL20.glDeleteShader(previewFragment);
            GL20.glDeleteProgram(program); GL20.glDeleteProgram(compositeProgram); GL20.glDeleteProgram(accumProgram); GL20.glDeleteProgram(revealProgram);
            for(int shader:new int[]{vertex,fragment,compositeFragment,accumFragment,revealFragment}) GL20.glDeleteShader(shader);
        }
    }
    public static void main(String[] args) throws Exception {
        RenderSystem.initRenderThread();
        require(SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO),SDLError.SDL_GetError());
        long window=0,context=0;
        try {
            SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MAJOR_VERSION,4);
            SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MINOR_VERSION,5);
            SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_PROFILE_MASK,SDLVideo.SDL_GL_CONTEXT_PROFILE_CORE);
            window=SDLVideo.SDL_CreateWindow("Original compute contract test",32,32,SDLVideo.SDL_WINDOW_HIDDEN|SDLVideo.SDL_WINDOW_OPENGL);
            require(window!=0,SDLError.SDL_GetError());
            context=SDLVideo.SDL_GL_CreateContext(window);
            require(context!=0,SDLError.SDL_GetError());
            require(SDLVideo.SDL_GL_MakeCurrent(window,context),SDLError.SDL_GetError());
            GL.createCapabilities();
            String renderer=GL11.glGetString(GL11.GL_RENDERER);
            System.out.println("GL renderer="+renderer);
            require(renderer.toLowerCase(Locale.ROOT).contains("intel"),"This validation must run on Intel");
            run();
            runPopulatedMultiRegion();
            runRegionLodLayers();
            runStormShadow();
            runStormFog();
            runStormBlur();
            runTransparency();
        } finally {
            GL.setCapabilities(null);
            if(context!=0) SDLVideo.SDL_GL_DestroyContext(context);
            if(window!=0) SDLVideo.SDL_DestroyWindow(window);
            SDLInit.SDL_Quit();
        }
    }
}

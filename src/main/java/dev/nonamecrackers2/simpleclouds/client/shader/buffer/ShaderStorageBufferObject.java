package dev.nonamecrackers2.simpleclouds.client.shader.buffer;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;

import org.lwjgl.opengl.GL45;
import com.mojang.blaze3d.systems.RenderSystem;

public class ShaderStorageBufferObject implements WithBinding
{
	private static final Logger LOGGER = LogManager.getLogger("simpleclouds/ShaderStorageBufferObject");
	private static int maxSize = -1;
	protected int id;
	protected final int binding;
	protected final int usage;
	protected @Nullable ByteBuffer buffer;
	private int allocatedBytes;
	private static final boolean PROFILE = "1".equals(System.getenv("SIMPLECLOUDS_PROFILE"));
	private long profileMapNs, profileConsumeNs, profileUnmapNs, profileMaxMapNs;
	private int profileSamples;
	private final java.util.Map<Integer, Integer> profileAccessCounts = PROFILE ? new java.util.TreeMap<>() : null;
	
	public ShaderStorageBufferObject(int id, int binding, int usage)
	{
		this.id = id;
		this.binding = binding;
		this.usage = usage;
	}
	
	public static int getMaxSize()
	{
		if (maxSize == -1)
			maxSize = GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE);
		return maxSize;
	}
	
	public void bindToProgram(String name, int programId)
	{
		this.bindToProgram(name, programId, true);
	}
	
	public void optionalBindToProgram(String name, int programId)
	{
		this.bindToProgram(name, programId, false);
	}
	
	private void bindToProgram(String name, int programId, boolean throwIfMissing)
	{
		RenderSystem.assertOnRenderThread();
		this.assertValid();
		int index = GL43.glGetProgramResourceIndex(programId, GL43.GL_SHADER_STORAGE_BLOCK, name);
		if (index == -1 && throwIfMissing)
			throw new NullPointerException("Unknown block index with name '" + name + "'");
		if (index != -1)
			GL43.glShaderStorageBlockBinding(programId, index, this.binding);
	}
	
	public void uploadData(ByteBuffer buffer)
	{
		int size = buffer.remaining();
		if (size > getMaxSize())
			throw new IllegalArgumentException("Size exceeds the SSBO maximum supported by current hardware, wanted: " + size + " bytes, maximum: " + maxSize + " bytes");
		RenderSystem.assertOnRenderThread();
		this.assertValid();
		GL45.glNamedBufferData(this.id, buffer, this.usage);
		this.allocatedBytes = size;
	}
	
	public int allocateBuffer(int bytes)
	{
		RenderSystem.assertOnRenderThread();
		this.assertValid();
		if (bytes <= 0) throw new IllegalArgumentException("Invalid buffer size: " + bytes);
		int size = Math.min(bytes, getMaxSize());
		// GPU allocation: do not keep a second, equally large native CPU buffer.
		GL45.glNamedBufferData(this.id, size, this.usage);
		GL45.glClearNamedBufferData(this.id, org.lwjgl.opengl.GL30.GL_R8UI,
			org.lwjgl.opengl.GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_BYTE, (ByteBuffer)null);
		this.allocatedBytes = size;
		return size;
	}
	
	@Override
	public void close()
	{
		RenderSystem.assertOnRenderThread();
		if (this.id != -1)
		{
			LOGGER.debug("Deleting buffer id={}, binding={}", this.id, this.binding);
			GL15.glDeleteBuffers(this.id);
			this.id = -1;
		}
		if (this.buffer != null)
		{
			MemoryUtil.memFree(this.buffer);
			this.buffer = null;
		}
	}
	
	public void fetchData(Consumer<ByteBuffer> consumer, int access, int size)
	{
		RenderSystem.assertOnRenderThread();
		this.assertValid();
		if (size <= 0 || size > this.allocatedBytes)
			throw new IllegalArgumentException("Invalid mapped size: " + size + ", allocated: " + this.allocatedBytes);
		long mapStart = PROFILE ? System.nanoTime() : 0;
		ByteBuffer mapped = GL45.glMapNamedBufferRange(this.id, 0, size, access, (ByteBuffer)null);
		long mapEnd = PROFILE ? System.nanoTime() : 0;
		if (mapped == null) throw new IllegalStateException("Could not map SSBO " + this.id);
		try {
			consumer.accept(mapped);
		} finally {
			long consumeEnd = PROFILE ? System.nanoTime() : 0;
			if (!GL45.glUnmapNamedBuffer(this.id))
				throw new IllegalStateException("SSBO mapping became invalid: " + this.id);
			if (PROFILE) {
				this.profileAccessCounts.merge(access, 1, Integer::sum);
				long mapNs = mapEnd - mapStart;
				this.profileMapNs += mapNs;
				this.profileConsumeNs += consumeEnd - mapEnd;
				this.profileUnmapNs += System.nanoTime() - consumeEnd;
				this.profileMaxMapNs = Math.max(this.profileMaxMapNs, mapNs);
				if (++this.profileSamples == 120) {
					LOGGER.info("[SSBO-TIMING] binding={} bytes={} accessCounts={} samples={} mapMeanMs={} mapMaxMs={} consumeMeanMs={} unmapMeanMs={}",
						this.binding, size, this.profileAccessCounts, this.profileSamples, this.profileMapNs / 120_000_000.0,
						this.profileMaxMapNs / 1_000_000.0, this.profileConsumeNs / 120_000_000.0,
						this.profileUnmapNs / 120_000_000.0);
					this.profileSamples = 0;
					this.profileMapNs = this.profileConsumeNs = this.profileUnmapNs = this.profileMaxMapNs = 0;
					this.profileAccessCounts.clear();
				}
			}
		}
	}
	
	public void readData(Consumer<ByteBuffer> consumer, int size)
	{
		this.fetchData(consumer, GL30.GL_MAP_READ_BIT, size);
	}
	
	public void writeData(Consumer<ByteBuffer> consumer, int size, boolean invalidate)
	{
		int access = GL30.GL_MAP_WRITE_BIT;
		if (invalidate)
			access |= GL30.GL_MAP_INVALIDATE_BUFFER_BIT;
		this.fetchData(consumer, access, size);
	}
	
	public void readWriteData(Consumer<ByteBuffer> consumer, int size)
	{
		this.fetchData(consumer, GL30.GL_MAP_WRITE_BIT | GL30.GL_MAP_READ_BIT, size);
	}
	
	@Override
	public int getBinding()
	{
		return this.binding;
	}
	
	public int getId()
	{
		return this.id;
	}
	
	public int getUsage()
	{
		return this.usage;
	}
	
	protected void assertValid()
	{
		if (this.id == -1)
			throw new IllegalStateException("Buffer is no longer valid!");
	}
	
	@Override
	public String toString()
	{
		return String.format("SSBO[binding=%s,id=%s,usage=%s]", this.binding, this.id, this.usage);
	}
}

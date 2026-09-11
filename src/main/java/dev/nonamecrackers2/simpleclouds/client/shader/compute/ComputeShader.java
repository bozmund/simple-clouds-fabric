package dev.nonamecrackers2.simpleclouds.client.shader.compute;

import java.io.IOException;
import java.util.List;
import java.util.function.BiConsumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.collect.ImmutableMap;

import dev.nonamecrackers2.simpleclouds.client.shader.buffer.ShaderStorageBufferObject;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * 26.2 vertical-slice stub. Compute shaders are gone from the new backend; the legacy
 * compute-based mesh generation is unused, so this is a no-op that keeps the API so
 * the old generator classes compile. Port (as CPU generation) incrementally.
 */
public class ComputeShader
{
	protected static final Logger LOGGER = LogManager.getLogger("simpleclouds/ComputeShader");
	private final String name;

	private ComputeShader(String name)
	{
		this.name = name;
	}

	public void close()
	{
	}

	public void forUniform(String name, BiConsumer<Integer, Integer> consumer)
	{
	}

	public void setSampler2D(String name, int texture, int id)
	{
	}

	public void setSampler3D(String name, int texture, int id)
	{
	}

	public void setSampler2DArray(String name, int texture, int id)
	{
	}

	public ShaderStorageBufferObject createAndBindSSBO(String name, int usage)
	{
		return null;
	}

	public int findAndUseSSBOBinding(String name)
	{
		return -1;
	}

	public void setImageUnit(String name, int unit)
	{
	}

	public ShaderStorageBufferObject getShaderStorageBuffer(String name)
	{
		return null;
	}

	public int getShaderStorageBinding(String name)
	{
		return -1;
	}

	public void dispatch(int groupX, int groupY, int groupZ, boolean wait)
	{
	}

	public void dispatchAndWait(int groupX, int groupY, int groupZ)
	{
	}

	public String getName()
	{
		return this.name;
	}

	public int getId()
	{
		return -1;
	}

	@Override
	public String toString()
	{
		return "ComputeShader[stub]" + this.name;
	}

	public boolean isValid()
	{
		return false;
	}

	public static ComputeShader loadShader(Identifier loc, ResourceManager provider, int localX, int localY, int localZ) throws IOException
	{
		return new ComputeShader(loc.getPath());
	}

	public static ComputeShader loadShader(Identifier loc, ResourceManager provider, int localX, int localY, int localZ, ImmutableMap<String, String> parameters) throws IOException
	{
		return new ComputeShader(loc.getPath());
	}
}

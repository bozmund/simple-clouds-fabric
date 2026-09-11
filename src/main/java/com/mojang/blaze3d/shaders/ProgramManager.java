package com.mojang.blaze3d.shaders;

import org.lwjgl.opengl.GL20;

/**
 * Fabric 26.2 compatibility: ProgramManager was removed in 26.2.
 * This is a minimal replacement to allow old rendering code to compile.
 */
public class ProgramManager
{
	public static void glUseProgram(int programId)
	{
		GL20.glUseProgram(programId);
	}

	public static int createProgram()
	{
		return GL20.glCreateProgram();
	}

	public static int createShader(int type)
	{
		return GL20.glCreateShader(type);
	}

	public static void shaderSource(int shaderId, String source)
	{
		GL20.glShaderSource(shaderId, source);
	}

	public static void compileShader(int shaderId)
	{
		GL20.glCompileShader(shaderId);
	}

	public static void attachShader(int programId, int shaderId)
	{
		GL20.glAttachShader(programId, shaderId);
	}

	public static void linkProgram(int programId)
	{
		GL20.glLinkProgram(programId);
	}

	public static int getProgramInfo(int programId, int pname)
	{
		return GL20.glGetProgrami(programId, pname);
	}

	public static int getShaderInfo(int shaderId, int pname)
	{
		return GL20.glGetShaderi(shaderId, pname);
	}

	public static String getShaderInfoLog(int shaderId)
	{
		return GL20.glGetShaderInfoLog(shaderId);
	}

	public static String getProgramInfoLog(int programId)
	{
		return GL20.glGetProgramInfoLog(programId);
	}
}

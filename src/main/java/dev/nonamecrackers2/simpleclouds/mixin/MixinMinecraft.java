package dev.nonamecrackers2.simpleclouds.mixin;

import org.jetbrains.annotations.Nullable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.nonamecrackers2.simpleclouds.client.compat.SimpleCloudsCompatHelper;
import dev.nonamecrackers2.simpleclouds.client.gui.SimpleCloudsErrorScreen;
import dev.nonamecrackers2.simpleclouds.client.gui.SimpleCloudsNoticeScreen;
import dev.nonamecrackers2.simpleclouds.client.mesh.RendererInitializeResult;
import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;
import dev.nonamecrackers2.simpleclouds.client.shader.buffer.BindingManager;
import dev.nonamecrackers2.simpleclouds.client.world.ClientCloudManager;
import dev.nonamecrackers2.simpleclouds.common.world.CloudManager;
import dev.nonamecrackers2.simpleclouds.common.world.CloudManagerHolder;
import net.minecraft.CrashReport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.client.extensions.IForgeMinecraft;

@Mixin(Minecraft.class)
public abstract class MixinMinecraft implements IForgeMinecraft
{
	@Inject(method = "fillReport", at = @At("HEAD"))
	public void simpleclouds$appendCrashReportDetails_fillReport(CrashReport report, CallbackInfoReturnable<CrashReport> ci)
	{
		SimpleCloudsRenderer.getOptionalInstance().ifPresent(renderer -> {
			renderer.fillReport(report);
		});
		BindingManager.fillReport(report);
	}

	// NOTE(26.2): the old `setInitialScreen` hook (startup error/notice screen) is
	// deferred — setInitialScreen() no longer exists in the 26.2 startup flow.
	
	@Inject(method = "setLevel", at = @At("TAIL"))
	public void simpleclouds$onClientLevelChange_setLevel(@Nullable ClientLevel level, CallbackInfo ci)
	{
		if (level instanceof CloudManagerHolder)
		{
			SimpleCloudsRenderer.getOptionalInstance().ifPresent(renderer -> 
			{
				ClientCloudManager manager = (ClientCloudManager)CloudManager.get(level);
				renderer.onCloudManagerChange(manager);
			});
		}
	}

}

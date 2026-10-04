package dev.nonamecrackers2.simpleclouds.client.command;

import com.mojang.brigadier.context.CommandContext;
import dev.nonamecrackers2.simpleclouds.common.command.CloudCommandSource;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.arguments.coordinates.WorldCoordinates;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec2;

/** Keeps Fabric client source/ClientLevel linkage entirely on the client side. */
public final class ClientCloudCommandAccess implements CloudCommandSource.ClientSourceAccess {
    public static void register() { CloudCommandSource.registerClientSourceAccess(new ClientCloudCommandAccess()); }
    private ClientCloudCommandAccess() {}
    public boolean supports(Object source) { return source instanceof FabricClientCommandSource; }
    public void sendSuccess(Object source, Component message) { ((FabricClientCommandSource)source).sendFeedback(message); }
    public void sendError(Object source, Component message) { ((FabricClientCommandSource)source).sendError(message); }
    public Level getLevel(Object source) { return ((FabricClientCommandSource)source).getLevel(); }
    public Vec2 getVec2Arg(CommandContext<?> context, String name) {
        var coordinates=context.getArgument(name,WorldCoordinates.class);
        return CloudCommandSource.resolveClientVec2(coordinates,((FabricClientCommandSource)context.getSource()).getPosition());
    }
}

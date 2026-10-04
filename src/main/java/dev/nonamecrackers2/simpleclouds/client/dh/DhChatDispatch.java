package dev.nonamecrackers2.simpleclouds.client.dh;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/** DH's render-thread queue can run inside an Iris pass. Chat glyph uploads cannot. */
public final class DhChatDispatch {
    private record Pending(ClientLevel level, LocalPlayer player, String message) {}
    private static final int LIMIT=256;
    private static final BoundedDeferredQueue<Pending> pending=new BoundedDeferredQueue<>(LIMIT);
    private static final org.apache.logging.log4j.Logger LOG=
            org.apache.logging.log4j.LogManager.getLogger("simpleclouds/DhChatDispatch");
    private static boolean logged;
    private DhChatDispatch() {}

    public static void enqueue(String message) {
        var mc=Minecraft.getInstance();
        if(mc.level==null || mc.player==null) return;
        if(pending.offer(new Pending(mc.level,mc.player,message))) {
            LOG.warn("DH chat queue exceeded {} entries; discarded oldest diagnostic message",LIMIT);
        }
    }

    /** Called outside rendering, never via Minecraft.execute (which may execute immediately). */
    public static void tick(Minecraft mc) {
        com.mojang.blaze3d.systems.RenderSystem.assertOnRenderThread();
        for(int i=0;i<16;i++) {
            Pending next=pending.poll();
            if(next==null) return;
            // Messages from a disconnected world must not appear in the next session.
            if(mc.level!=next.level || mc.player!=next.player) continue;
            next.player.sendSystemMessage(Component.translatable(next.message));
            if(!logged) {
                logged=true;
                LOG.info("[DH-CHAT] delivered on client tick, outside render-pass task queue");
            }
        }
    }
}

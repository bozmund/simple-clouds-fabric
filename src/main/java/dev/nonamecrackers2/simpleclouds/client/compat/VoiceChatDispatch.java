package dev.nonamecrackers2.simpleclouds.client.compat;

import dev.nonamecrackers2.simpleclouds.client.dh.BoundedDeferredQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;

/** Voice Chat 2.6.x may send errors from MicrophoneThread. Glyph uploads belong
 * on a client tick, not another thread or an immediate render-pass task. */
public final class VoiceChatDispatch {
    private record Pending(ClientLevel level,Component message,String sourceThread) {}
    private static final BoundedDeferredQueue<Pending> pending=new BoundedDeferredQueue<>(256);
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(VoiceChatDispatch.class);
    private static boolean logged;
    private VoiceChatDispatch() {}
    public static void enqueue(Component message) {
        if(pending.offer(new Pending(Minecraft.getInstance().level,message.copy(),Thread.currentThread().getName())))
            LOG.warn("Voice Chat message queue full; discarded oldest diagnostic");
    }
    public static void tick(Minecraft mc) {
        com.mojang.blaze3d.systems.RenderSystem.assertOnRenderThread();
        for(int i=0;i<16;i++) {
            Pending next=pending.poll();
            if(next==null)return;
            if(mc.level!=next.level)continue; // Do not leak an old connection's messages.
            mc.gui.hud.getChat().addClientSystemMessage(next.message);
            if(!logged) {
                logged=true;
                LOG.info("[VOICECHAT-CHAT] delivered sourceThread={} on client tick, outside render pass",next.sourceThread);
            }
        }
    }
}

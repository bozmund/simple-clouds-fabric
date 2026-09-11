package net.minecraftforge.network;
public class PacketDistributor {
    public static final PacketTarget ALL = new PacketTarget() {
        public void send(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {}
    };
    public static class PacketTarget {
        public void send(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {}
        public static PacketTarget PLAYER = new PacketTarget();
        public static PacketTarget DIMENSION = new PacketTarget();
        public PacketTarget with(java.util.function.Supplier<net.minecraft.server.level.ServerPlayer> player) { return this; }
        public PacketTarget noArg() { return this; }
    }
}

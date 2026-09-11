package net.minecraftforge.event;
import net.minecraftforge.eventbus.api.Event;
public class TickEvent extends Event {
    public enum Phase { START, END }
    public static class LevelTickEvent extends TickEvent {
        public net.minecraft.world.level.Level level;
        public Phase phase;
    }
    public static class PlayerTickEvent extends TickEvent {
        public net.minecraft.world.entity.player.Player player;
        public Phase phase;
    }
    public static class ClientTickEvent extends TickEvent {
        public Phase phase;
    }
}

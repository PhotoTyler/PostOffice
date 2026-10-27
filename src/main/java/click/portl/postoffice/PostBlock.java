package click.portl.postoffice;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/** A placed mailbox or drop-off. The world block is a barrier; the look comes from an ItemDisplay. */
final class PostBlock {
    enum Type {
        MAILBOX_UK(MailKind.MAILBOX_UK), MAILBOX_US(MailKind.MAILBOX_US), DROPOFF(MailKind.DROPOFF);

        final MailKind kind;

        Type(MailKind kind) {
            this.kind = kind;
        }

        boolean isMailbox() {
            return this != DROPOFF;
        }

        static Type of(MailKind kind) {
            for (Type t : values()) if (t.kind == kind) return t;
            return null;
        }
    }

    final UUID id;
    final Type type;
    final UUID owner;
    String name;
    final String world;
    final int x, y, z;
    final float yaw;

    PostBlock(UUID id, Type type, UUID owner, String name, String world, int x, int y, int z, float yaw) {
        this.id = id;
        this.type = type;
        this.owner = owner;
        this.name = name;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
    }

    World bukkitWorld() {
        return Bukkit.getWorld(world);
    }

    Block block() {
        World w = bukkitWorld();
        return w == null ? null : w.getBlockAt(x, y, z);
    }

    Location center() {
        return new Location(bukkitWorld(), x + 0.5, y + 0.5, z + 0.5);
    }

    BlockKey key() {
        return new BlockKey(world, x, y, z);
    }

    /** Drop-offs are pillar boxes two blocks tall. */
    int height() {
        return type == Type.DROPOFF ? 2 : 1;
    }

    boolean isLoaded() {
        World w = bukkitWorld();
        return w != null && w.isChunkLoaded(x >> 4, z >> 4);
    }
}

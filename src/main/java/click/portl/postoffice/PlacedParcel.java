package click.portl.postoffice;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/** A parcel put down on the ground, waiting to be opened. */
final class PlacedParcel {
    final UUID id;
    final String world;
    final int x, y, z;
    final float yaw;
    final String payload; // Base64 ItemStack
    boolean open;

    PlacedParcel(UUID id, String world, int x, int y, int z, float yaw, String payload, boolean open) {
        this.id = id;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.payload = payload;
        this.open = open;
    }

    World bukkitWorld() {
        return Bukkit.getWorld(world);
    }

    Location center() {
        return new Location(bukkitWorld(), x + 0.5, y + 0.5, z + 0.5);
    }

    BlockKey key() {
        return new BlockKey(world, x, y, z);
    }
}

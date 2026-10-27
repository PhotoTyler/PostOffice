package click.portl.postoffice;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/** A box on the ground: open (being packed or unpacked) or sealed. */
final class PlacedParcel {
    final UUID id;
    final String world;
    final int x, y, z;
    final float yaw;
    final List<String> contents; // Base64 item stacks, at most Parcels.MAX
    boolean open;

    PlacedParcel(UUID id, String world, int x, int y, int z, float yaw, List<String> contents, boolean open) {
        this.id = id;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.contents = new ArrayList<>(contents);
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

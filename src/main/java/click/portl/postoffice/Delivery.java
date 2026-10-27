package click.portl.postoffice;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/** One letter or parcel waiting for, or travelling with, a Postman. Saved in queue.yml until delivered. */
final class Delivery {
    final UUID id;
    final UUID sender;
    final String senderName;
    UUID recipient;
    UUID mailbox;
    final String item;          // Base64 ItemStack
    final String world;         // drop-off it was posted at
    final int x, y, z;
    final long created;
    boolean returning;          // being sent back to the sender

    transient boolean assigned; // a Postman is carrying it right now

    Delivery(UUID id, UUID sender, String senderName, UUID recipient, UUID mailbox, String item,
             String world, int x, int y, int z, long created) {
        this.id = id;
        this.sender = sender;
        this.senderName = senderName;
        this.recipient = recipient;
        this.mailbox = mailbox;
        this.item = item;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.created = created;
    }

    String originKey() {
        return world + "," + x + "," + y + "," + z;
    }

    Location origin() {
        World w = Bukkit.getWorld(world);
        return w == null ? null : new Location(w, x + 0.5, y, z + 0.5);
    }
}

package click.portl.postoffice;

import java.util.List;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

/**
 * For add-on plugins: make letters and parcels and have the Postman bring them to a player's mailbox.
 * Call from the main thread.
 */
public final class PostOfficeAPI {
    private PostOfficeAPI() {}

    private static PostOfficePlugin plugin() {
        return PostOfficePlugin.getPlugin(PostOfficePlugin.class);
    }

    /** True if the player owns at least one mailbox. */
    public static boolean hasMailbox(UUID player) {
        return !plugin().blocks().mailboxesOf(player).isEmpty();
    }

    /** A sealed letter from {@code from} with these pages (plain text, one string per page). */
    public static ItemStack letter(String from, List<String> pages) {
        return MailItems.sealedLetter(pages, from, null);
    }

    /** A sealed parcel from {@code from} holding up to 8 item stacks. */
    public static ItemStack parcel(String from, List<ItemStack> contents) {
        return MailItems.parcel(contents.size() > 8 ? contents.subList(0, 8) : contents, from);
    }

    /**
     * The Postman appears out of sight near the player's (first) mailbox and walks the letter or
     * parcel to it. Returns false if the player has no mailbox or the item isn't PostOffice mail.
     */
    public static boolean deliver(UUID player, ItemStack mail) {
        MailKind kind = MailItems.kind(mail);
        if (kind != MailKind.SEALED_LETTER && kind != MailKind.PARCEL) return false;
        List<PostBlock> boxes = plugin().blocks().mailboxesOf(player);
        if (boxes.isEmpty()) return false;
        PostBlock box = boxes.getFirst();
        String from = MailItems.sender(mail);
        // Sender = the player themself, so anything undeliverable lands back in their own pocket.
        plugin().post().post(new Delivery(UUID.randomUUID(), player, from == null ? "?" : from, player, box.id,
                ItemCodec.encode(mail), box.world, box.x, box.y, box.z, System.currentTimeMillis()));
        return true;
    }
}

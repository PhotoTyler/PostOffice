package click.portl.postoffice;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/** All PersistentDataContainer keys and resource pack model ids. */
final class Keys {
    static final String NS = "postoffice";

    static NamespacedKey TYPE;        // MailKind id on every PostOffice item
    static NamespacedKey UNIQUE;      // random id so mail never stacks and can be found again
    static NamespacedKey PAGES;       // sealed letter text
    static NamespacedKey SENDER;      // shown sender name (editable on letters)
    static NamespacedKey ATTACHMENT;  // Base64 item attached to a letter
    static NamespacedKey PAYLOAD;     // Base64 item stack inside a parcel
    static NamespacedKey READ;        // sealed letter has been opened
    static NamespacedKey POSTMAN;     // tag on the Postman villager and its hat
    static NamespacedKey BLOCK_ID;    // tag on mailbox / drop-off display entities
    static NamespacedKey PARCEL_ID;   // tag on placed parcel display entities
    static NamespacedKey ROLE;        // which display entity this is (model, holo, flap...)

    static NamespacedKey RECIPE_LETTER;
    static NamespacedKey RECIPE_EMPTY_BOX;
    static NamespacedKey RECIPE_PARCEL_CHEST;
    static NamespacedKey RECIPE_PARCEL_BOX;
    static NamespacedKey RECIPE_MAILBOX_UK;
    static NamespacedKey RECIPE_MAILBOX_US;
    static NamespacedKey RECIPE_DROPOFF;

    private Keys() {}

    static void init(Plugin plugin) {
        TYPE = new NamespacedKey(plugin, "type");
        UNIQUE = new NamespacedKey(plugin, "unique");
        PAGES = new NamespacedKey(plugin, "pages");
        SENDER = new NamespacedKey(plugin, "sender");
        ATTACHMENT = new NamespacedKey(plugin, "attachment");
        PAYLOAD = new NamespacedKey(plugin, "payload");
        READ = new NamespacedKey(plugin, "read");
        POSTMAN = new NamespacedKey(plugin, "postman");
        BLOCK_ID = new NamespacedKey(plugin, "block_id");
        PARCEL_ID = new NamespacedKey(plugin, "parcel_id");
        ROLE = new NamespacedKey(plugin, "role");

        RECIPE_LETTER = new NamespacedKey(plugin, "letter");
        RECIPE_EMPTY_BOX = new NamespacedKey(plugin, "empty_box");
        RECIPE_PARCEL_CHEST = new NamespacedKey(plugin, "parcel_chest");
        RECIPE_PARCEL_BOX = new NamespacedKey(plugin, "parcel_box");
        RECIPE_MAILBOX_UK = new NamespacedKey(plugin, "mailbox_uk");
        RECIPE_MAILBOX_US = new NamespacedKey(plugin, "mailbox_us");
        RECIPE_DROPOFF = new NamespacedKey(plugin, "dropoff");
    }

    /** Item model id inside the resource pack, e.g. postoffice:letter. */
    static NamespacedKey model(String name) {
        return new NamespacedKey(NS, name);
    }
}

package click.portl.postoffice;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.WritableBookMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/** Creates and reads the custom items. All state lives in the item's PersistentDataContainer. */
final class MailItems {
    private static PostOfficePlugin plugin;

    private MailItems() {}

    static void init(PostOfficePlugin p) {
        plugin = p;
    }

    // ---- identification ----

    static MailKind kind(ItemStack item) {
        if (ItemCodec.isEmpty(item) || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(Keys.TYPE, PersistentDataType.STRING);
        return id == null ? null : MailKind.byId(id);
    }

    static String uniqueId(ItemStack item) {
        if (ItemCodec.isEmpty(item) || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(Keys.UNIQUE, PersistentDataType.STRING);
    }

    /** Gives the item a unique id if it has none (letters are crafted without one). */
    static String ensureUnique(ItemStack item) {
        String id = uniqueId(item);
        if (id != null) return id;
        String fresh = UUID.randomUUID().toString();
        item.editMeta(m -> m.getPersistentDataContainer().set(Keys.UNIQUE, PersistentDataType.STRING, fresh));
        return fresh;
    }

    /** Finds the inventory slot holding the item with this unique id, preferring {@code hint}. */
    static int findSlot(Player p, String unique, int hint) {
        ItemStack[] contents = p.getInventory().getContents();
        if (hint >= 0 && hint < contents.length && unique.equals(uniqueId(contents[hint]))) return hint;
        for (int i = 0; i < contents.length; i++) if (unique.equals(uniqueId(contents[i]))) return i;
        return -1;
    }

    // ---- creation ----

    static ItemStack create(MailKind kind) {
        ItemStack item = new ItemStack(kind.material);
        item.editMeta(m -> {
            m.getPersistentDataContainer().set(Keys.TYPE, PersistentDataType.STRING, kind.id);
            m.setItemModel(Keys.model(kind.id));
            m.itemName(Msg.item(name(kind)));
            m.setMaxStackSize(kind.maxStack);
            // Blank letters stack; written ones get a unique id (and a stack size of 1) when written.
            if (kind.sendable() && kind != MailKind.LETTER) {
                m.getPersistentDataContainer().set(Keys.UNIQUE, PersistentDataType.STRING, UUID.randomUUID().toString());
            }
        });
        updateLore(item);
        return item;
    }

    /** A blank letter (stackable, no unique id yet). */
    static ItemStack blankLetter() {
        return create(MailKind.LETTER);
    }

    /** Saves text and sender into an open letter. */
    static void writeLetter(ItemStack item, String text, String sender) {
        ensureUnique(item);
        item.editMeta(m -> {
            PersistentDataContainer pdc = m.getPersistentDataContainer();
            pdc.set(Keys.PAGES, PersistentDataType.LIST.strings(), List.of(text));
            if (sender != null) pdc.set(Keys.SENDER, PersistentDataType.STRING, sender);
            m.setMaxStackSize(1);
        });
        updateLore(item);
    }

    /** Puts an open letter into its envelope. */
    static void seal(ItemStack item) {
        ensureUnique(item);
        item.editMeta(m -> {
            m.getPersistentDataContainer().set(Keys.TYPE, PersistentDataType.STRING, MailKind.SEALED_LETTER.id);
            m.getPersistentDataContainer().remove(Keys.READ);
            m.setItemModel(Keys.model(MailKind.SEALED_LETTER.id));
            m.itemName(Msg.item(name(MailKind.SEALED_LETTER)));
            m.setMaxStackSize(1);
        });
        updateLore(item);
    }

    /** Opens an envelope: it becomes an open letter you can read, change and reseal. */
    static void unseal(ItemStack item) {
        item.editMeta(m -> {
            m.getPersistentDataContainer().set(Keys.TYPE, PersistentDataType.STRING, MailKind.LETTER.id);
            m.getPersistentDataContainer().set(Keys.READ, PersistentDataType.BOOLEAN, true);
            m.setItemModel(Keys.model("letter_open"));
            m.itemName(Msg.item(plugin.getConfig().getString("items.letter.opened-name", "<white>Opened Letter")));
            m.setMaxStackSize(1);
        });
        updateLore(item);
    }

    static ItemStack parcel(ItemStack payload, String sender) {
        ItemStack item = create(MailKind.PARCEL);
        item.editMeta(m -> {
            PersistentDataContainer pdc = m.getPersistentDataContainer();
            pdc.set(Keys.PAYLOAD, PersistentDataType.STRING, ItemCodec.encode(payload));
            if (sender != null) pdc.set(Keys.SENDER, PersistentDataType.STRING, sender);
        });
        updateLore(item);
        return item;
    }

    static ItemStack sealedLetter(List<String> pages, String sender, ItemStack attachment) {
        ItemStack item = create(MailKind.SEALED_LETTER);
        item.editMeta(m -> {
            PersistentDataContainer pdc = m.getPersistentDataContainer();
            pdc.set(Keys.PAGES, PersistentDataType.LIST.strings(), pages);
            pdc.set(Keys.SENDER, PersistentDataType.STRING, sender);
            if (!ItemCodec.isEmpty(attachment)) pdc.set(Keys.ATTACHMENT, PersistentDataType.STRING, ItemCodec.encode(attachment));
        });
        updateLore(item);
        return item;
    }

    /** What actually goes into the post: a written letter becomes a sealed envelope, parcels get a sender. */
    static ItemStack prepareForPost(ItemStack item, Player sender) {
        MailKind kind = kind(item);
        if (kind == MailKind.LETTER && item.getType() != MailKind.LETTER.material) {
            // old book & quill letter
            String from = sender(item);
            return sealedLetter(pages(item), from == null ? sender.getName() : from, attachment(item));
        }
        ItemStack copy = item.clone();
        copy.setAmount(1);
        if (kind == MailKind.LETTER) {
            if (sender(copy) == null) copy.editMeta(m -> m.getPersistentDataContainer().set(Keys.SENDER, PersistentDataType.STRING, sender.getName()));
            seal(copy); // letters always travel in an envelope
            return copy;
        }
        if (kind == MailKind.PARCEL && sender(copy) == null) {
            copy.editMeta(m -> m.getPersistentDataContainer().set(Keys.SENDER, PersistentDataType.STRING, sender.getName()));
            updateLore(copy);
        }
        return copy;
    }

    // ---- reading / writing state ----

    static String sender(ItemStack item) {
        return item.getItemMeta().getPersistentDataContainer().get(Keys.SENDER, PersistentDataType.STRING);
    }

    static void setSender(ItemStack item, String sender) {
        item.editMeta(m -> m.getPersistentDataContainer().set(Keys.SENDER, PersistentDataType.STRING, sender));
        updateLore(item);
    }

    static ItemStack attachment(ItemStack item) {
        return ItemCodec.decode(item.getItemMeta().getPersistentDataContainer().get(Keys.ATTACHMENT, PersistentDataType.STRING));
    }

    static void setAttachment(ItemStack item, ItemStack attachment) {
        item.editMeta(m -> {
            if (ItemCodec.isEmpty(attachment)) m.getPersistentDataContainer().remove(Keys.ATTACHMENT);
            else m.getPersistentDataContainer().set(Keys.ATTACHMENT, PersistentDataType.STRING, ItemCodec.encode(attachment));
        });
        updateLore(item);
    }

    static ItemStack payload(ItemStack parcel) {
        return ItemCodec.decode(parcel.getItemMeta().getPersistentDataContainer().get(Keys.PAYLOAD, PersistentDataType.STRING));
    }

    static String payloadData(ItemStack parcel) {
        return parcel.getItemMeta().getPersistentDataContainer().get(Keys.PAYLOAD, PersistentDataType.STRING);
    }

    /** Letter text: from the book for an unsent letter, from the PDC for a sealed one. */
    static List<String> pages(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        List<String> stored = meta.getPersistentDataContainer().get(Keys.PAGES, PersistentDataType.LIST.strings());
        if (stored != null) return stored;
        List<String> out = new ArrayList<>();
        if (meta instanceof BookMeta book) {
            for (Component c : book.pages()) out.add(PlainTextComponentSerializer.plainText().serialize(c));
        } else if (meta instanceof WritableBookMeta book) {
            out.addAll(book.getPages());
        }
        out.removeIf(String::isBlank);
        return out;
    }

    static boolean isRead(ItemStack item) {
        return item.getItemMeta().getPersistentDataContainer().has(Keys.READ);
    }

    static void markRead(ItemStack item) {
        item.editMeta(m -> {
            m.getPersistentDataContainer().set(Keys.READ, PersistentDataType.BOOLEAN, true);
            m.setItemModel(Keys.model("letter_open"));
        });
        updateLore(item);
    }

    // ---- rules ----

    /** Letters take exactly one item; never shulker boxes, bundles or other mail. */
    static boolean allowedOnLetter(ItemStack item) {
        if (kind(item) != null) return false;
        String type = item.getType().name();
        if (type.endsWith("SHULKER_BOX") || type.endsWith("BUNDLE")) return false;
        return !plugin.getConfig().getStringList("letters.blocked-attachments").contains(type);
    }

    static boolean allowedInParcel(ItemStack item) {
        if (kind(item) != null) return false;
        return !plugin.getConfig().getStringList("parcels.blocked-items").contains(item.getType().name());
    }

    // ---- display ----

    static String name(MailKind kind) {
        return plugin.getConfig().getString("items." + kind.id + ".name", kind.id);
    }

    static String displayName(ItemStack item) {
        return PlainTextComponentSerializer.plainText().serialize(item.effectiveName());
    }

    static void updateLore(ItemStack item) {
        MailKind kind = kind(item);
        if (kind == null) return;
        List<Component> lore = new ArrayList<>();
        String sender = sender(item);
        switch (kind) {
            case LETTER -> {
                ItemStack att = attachment(item);
                if (sender != null) lore.add(Msg.item(plugin.getConfig().getString("items.lore.from"), "sender", sender));
                if (att != null) lore.add(Msg.item(plugin.getConfig().getString("items.lore.attached"), "item", displayName(att)));
            }
            case SEALED_LETTER -> {
                if (sender != null) lore.add(Msg.item(plugin.getConfig().getString("items.lore.from"), "sender", sender));
                ItemStack att = attachment(item);
                if (att != null) lore.add(Msg.item(plugin.getConfig().getString("items.lore.something-inside")));
                if (isRead(item)) lore.add(Msg.item(plugin.getConfig().getString("items.lore.opened")));
            }
            case PARCEL -> {
                if (sender != null) lore.add(Msg.item(plugin.getConfig().getString("items.lore.from"), "sender", sender));
            }
            default -> {}
        }
        for (String line : plugin.getConfig().getStringList("items." + kind.id + ".lore")) lore.add(Msg.item(line));
        item.editMeta(m -> m.lore(lore));
    }

    /** A quick look at whether a material counts as an item we can put in recipe choices. */
    static List<Material> allItems() {
        List<Material> out = new ArrayList<>();
        for (Material m : Material.values()) {
            if (m.isItem() && !m.isAir() && !m.name().startsWith("LEGACY_")) out.add(m);
        }
        return out;
    }
}

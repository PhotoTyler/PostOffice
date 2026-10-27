package click.portl.postoffice;

import org.bukkit.Material;

/** Every custom PostOffice item. The id is stored in the item's PDC and is also the default model name. */
enum MailKind {
    LETTER("letter", Material.WRITABLE_BOOK, 16),
    SEALED_LETTER("sealed_letter", Material.PAPER, 1),
    PARCEL("parcel", Material.PAPER, 1),
    EMPTY_BOX("empty_box", Material.PAPER, 16),
    MAILBOX_UK("mailbox_uk", Material.PAPER, 16),
    MAILBOX_US("mailbox_us", Material.PAPER, 16),
    DROPOFF("dropoff", Material.PAPER, 16);

    final String id;
    final Material material;
    final int maxStack;

    MailKind(String id, Material material, int maxStack) {
        this.id = id;
        this.material = material;
        this.maxStack = maxStack;
    }

    boolean sendable() {
        return this == LETTER || this == SEALED_LETTER || this == PARCEL;
    }

    boolean isBlock() {
        return this == MAILBOX_UK || this == MAILBOX_US || this == DROPOFF;
    }

    static MailKind byId(String id) {
        for (MailKind k : values()) if (k.id.equals(id)) return k;
        return null;
    }
}

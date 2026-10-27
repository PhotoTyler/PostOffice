package click.portl.postoffice;

import java.util.Map;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Attaching an item to a letter you're writing, and reading a letter you received. */
final class LetterMenus {
    private LetterMenus() {}

    static void giveBack(Player p, ItemStack item) {
        if (ItemCodec.isEmpty(item)) return;
        Map<Integer, ItemStack> left = p.getInventory().addItem(item);
        for (ItemStack l : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), l);
    }

    /** "Attach item" on the letter screen: one free slot for the attachment (take it out to claim it). */
    static final class Attach extends Menu {
        private static final int SLOT = 13;
        private final String letterId;
        private final int hintSlot;
        private boolean saved;

        Attach(PostOfficePlugin plugin, Player p, int hintSlot) {
            super(plugin, 3, Msg.get("letter.attach-title"));
            this.hintSlot = hintSlot;
            ItemStack letter = p.getInventory().getItem(hintSlot);
            this.letterId = MailItems.ensureUnique(letter);
            // Move the attachment out of the letter while the menu is open, so it can't be duplicated.
            ItemStack att = MailItems.attachment(letter);
            MailItems.setAttachment(letter, null);
            inventory.setItem(SLOT, att);

            button(11, icon(Material.PAPER, "<yellow>Attach an item",
                    "<gray>Put <white>one</white> item in the middle slot.",
                    "<gray>No stacks, shulker boxes or bundles."), null);
            button(22, icon(Material.LIME_DYE, "<green>Done"), Player::closeInventory);
            fill();
        }

        @Override
        boolean isFreeSlot(int slot) {
            return slot == SLOT;
        }

        @Override
        void onClose(Player p) {
            if (saved) return;
            saved = true;
            ItemStack att = inventory.getItem(SLOT);
            inventory.setItem(SLOT, null);
            if (!ItemCodec.isEmpty(att)) {
                if (!MailItems.allowedOnLetter(att)) {
                    giveBack(p, att);
                    Msg.send(p, "letter.not-allowed");
                    att = null;
                } else if (att.getAmount() > 1) {
                    ItemStack extra = att.clone();
                    extra.setAmount(att.getAmount() - 1);
                    att.setAmount(1);
                    giveBack(p, extra);
                    Msg.send(p, "letter.only-one");
                }
            }
            int slot = MailItems.findSlot(p, letterId, hintSlot);
            if (slot < 0) {
                giveBack(p, att);
                return;
            }
            MailItems.setAttachment(p.getInventory().getItem(slot), att);
            if (!ItemCodec.isEmpty(att)) {
                p.playSound(p.getLocation(), Sound.ITEM_BUNDLE_INSERT, 1f, 1.2f);
                Msg.send(p, "letter.attached", "item", MailItems.displayName(att));
            }
        }
    }
}

package click.portl.postoffice;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
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

    /** Sneak + right-click a letter: one free slot for the attachment, plus the sender name. */
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
            String sender = MailItems.sender(letter);
            // Move the attachment out of the letter while the menu is open, so it can't be duplicated.
            ItemStack att = MailItems.attachment(letter);
            MailItems.setAttachment(letter, null);
            inventory.setItem(SLOT, att);

            button(11, icon(Material.PAPER, "<yellow>Attach an item",
                    "<gray>Put <white>one</white> item in the middle slot.",
                    "<gray>No stacks, shulker boxes or bundles."), null);
            button(15, icon(Material.NAME_TAG, "<yellow>Sender name",
                    "<gray>Now: <white>" + PostBlocks.escape(sender == null ? p.getName() : sender),
                    "<gray>Click to change"), player -> {
                player.closeInventory();
                plugin.prompts().ask(player, "letter.sender-prompt", text -> {
                    int slot = MailItems.findSlot(player, letterId, hintSlot);
                    if (slot < 0) return;
                    String name = text.length() > 32 ? text.substring(0, 32) : text;
                    MailItems.setSender(player.getInventory().getItem(slot), name);
                    Msg.send(player, "letter.sender-set", "sender", name);
                    new Attach(plugin, player, slot).open(player);
                });
            });
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

    /** Right-click a received letter: the text, and the attached item to claim. */
    static final class Read extends Menu {
        private final String letterId;
        private final int hintSlot;

        Read(PostOfficePlugin plugin, Player p, int hintSlot) {
            super(plugin, 3, Msg.get("letter.read-title"));
            this.hintSlot = hintSlot;
            ItemStack letter = p.getInventory().getItem(hintSlot);
            this.letterId = MailItems.ensureUnique(letter);
            if (!MailItems.isRead(letter)) {
                MailItems.markRead(letter);
                p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
            }
            draw(p, letter);
        }

        private void draw(Player p, ItemStack letter) {
            clearButtons();
            String sender = MailItems.sender(letter);
            List<String> pages = MailItems.pages(letter);

            List<Component> lore = new ArrayList<>();
            lore.add(Msg.item("<gray>From: <white>" + PostBlocks.escape(sender == null ? "?" : sender)));
            lore.add(Component.empty());
            int lines = 0;
            outer:
            for (String page : pages) {
                for (String line : wrap(page, 32)) {
                    if (lines++ >= 14) {
                        lore.add(Msg.item("<dark_gray>..."));
                        break outer;
                    }
                    lore.add(Msg.item("<white>" + PostBlocks.escape(line)));
                }
            }
            if (pages.isEmpty()) lore.add(Msg.item("<dark_gray><italic>(the letter is blank)"));
            lore.add(Component.empty());
            lore.add(Msg.item("<yellow>Click to read it as a book"));
            button(11, icon(Displays.modelItem("letter_open"), "<white>Letter", lore), player -> {
                player.closeInventory();
                List<Component> bookPages = new ArrayList<>();
                for (String page : pages) bookPages.add(Component.text(page));
                if (bookPages.isEmpty()) bookPages.add(Component.empty());
                player.openBook(Book.book(Component.text("Letter"), Component.text(sender == null ? "?" : sender), bookPages));
            });

            ItemStack att = MailItems.attachment(letter);
            if (att != null) {
                ItemStack shown = att.clone();
                shown.editMeta(m -> {
                    List<Component> l = m.hasLore() ? new ArrayList<>(m.lore()) : new ArrayList<>();
                    l.add(Component.empty());
                    l.add(Msg.item("<green>Click to take it"));
                    m.lore(l);
                });
                inventory.setItem(15, shown);
                button(15, shown, this::claim);
            } else {
                button(15, icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<gray>Nothing attached"), null);
            }
            button(22, icon(Material.BARRIER, "<red>Close"), Player::closeInventory);
            fill();
        }

        private void claim(Player p) {
            int slot = MailItems.findSlot(p, letterId, hintSlot);
            if (slot < 0) {
                p.closeInventory();
                return;
            }
            ItemStack letter = p.getInventory().getItem(slot);
            ItemStack att = MailItems.attachment(letter);
            if (att == null) return;
            MailItems.setAttachment(letter, null);
            Map<Integer, ItemStack> left = p.getInventory().addItem(att);
            if (!left.isEmpty()) {
                // No room: put it back in the letter.
                MailItems.setAttachment(p.getInventory().getItem(MailItems.findSlot(p, letterId, slot)), left.values().iterator().next());
                Msg.send(p, "letter.no-room");
                return;
            }
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
            Msg.send(p, "letter.claimed", "item", MailItems.displayName(att));
            draw(p, p.getInventory().getItem(MailItems.findSlot(p, letterId, slot)));
        }

        static List<String> wrap(String text, int width) {
            List<String> out = new ArrayList<>();
            for (String para : text.split("\n")) {
                StringBuilder line = new StringBuilder();
                for (String word : para.split(" ")) {
                    if (line.length() + word.length() + 1 > width && !line.isEmpty()) {
                        out.add(line.toString());
                        line.setLength(0);
                    }
                    if (!line.isEmpty()) line.append(' ');
                    line.append(word);
                }
                out.add(line.toString());
            }
            return out;
        }
    }
}

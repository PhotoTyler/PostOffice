package click.portl.postoffice;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

/** Drop-off routing: screen 1 picks the player, screen 2 picks their mailbox, then Send. */
final class SendMenus {
    private SendMenus() {}

    /** What is being sent, from which inventory slot, at which drop-off. */
    record Context(Player sender, int slot, ItemStack snapshot, PostBlock dropoff) {}

    static final class PickPlayer extends Menu {
        private static final int PER_PAGE = 45;

        PickPlayer(PostOfficePlugin plugin, Context ctx, int page) {
            super(plugin, 6, Msg.get("send.title-players"));
            List<UUID> players = plugin.blocks().registeredPlayers();
            int pages = Math.max(1, (players.size() + PER_PAGE - 1) / PER_PAGE);
            int start = page * PER_PAGE;
            for (int i = 0; i < PER_PAGE && start + i < players.size(); i++) {
                UUID uuid = players.get(start + i);
                String name = PostBlocks.nameOf(uuid);
                boolean online = Bukkit.getPlayer(uuid) != null;
                int boxes = plugin.blocks().mailboxesOf(uuid).size();
                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                head.editMeta(SkullMeta.class, m -> {
                    m.setOwningPlayer(Bukkit.getOfflinePlayer(uuid));
                    m.displayName(Msg.item((online ? "<green>" : "<gray>") + PostBlocks.escape(name)));
                    m.lore(List.of(
                            Msg.item(plugin.getConfig().getString(online ? "messages.send.online" : "messages.send.offline")),
                            Msg.item(plugin.getConfig().getString("messages.send.mailbox-count"), "count", String.valueOf(boxes)),
                            Msg.item(plugin.getConfig().getString("messages.send.click-player"))));
                });
                button(i, head, p -> new PickMailbox(plugin, ctx, uuid, null).open(p));
            }
            if (page > 0) button(45, icon(Material.ARROW, "<yellow>Previous page"), p -> new PickPlayer(plugin, ctx, page - 1).open(p));
            if (page + 1 < pages) button(53, icon(Material.ARROW, "<yellow>Next page"), p -> new PickPlayer(plugin, ctx, page + 1).open(p));
            button(49, sendingIcon(ctx), null);
            fill();
        }
    }

    static final class PickMailbox extends Menu {
        PickMailbox(PostOfficePlugin plugin, Context ctx, UUID recipient, UUID selected) {
            super(plugin, 6, Msg.get("send.title-mailboxes", "player", PostBlocks.nameOf(recipient)));
            List<PostBlock> boxes = plugin.blocks().mailboxesOf(recipient);
            UUID chosen = selected;
            if (chosen == null && boxes.size() == 1) chosen = boxes.getFirst().id;
            for (int i = 0; i < boxes.size() && i < 45; i++) {
                PostBlock pb = boxes.get(i);
                boolean isChosen = pb.id.equals(chosen);
                ItemStack icon = Displays.modelItem(pb.type.kind.id);
                List<Component> lore = new ArrayList<>();
                lore.add(Msg.item("<gray>" + pb.world + " " + pb.x + ", " + pb.y + ", " + pb.z));
                lore.add(Msg.item(plugin.getConfig().getString(isChosen ? "messages.send.selected" : "messages.send.click-mailbox")));
                ItemStack it = icon(icon, (isChosen ? "<green><bold>" : "<white>") + PostBlocks.escape(pb.name), lore);
                if (isChosen) it.editMeta(m -> m.setEnchantmentGlintOverride(true));
                button(i, it, p -> new PickMailbox(plugin, ctx, recipient, pb.id).open(p));
            }
            button(45, icon(Material.ARROW, "<yellow>Back"), p -> new PickPlayer(plugin, ctx, 0).open(p));
            button(49, sendingIcon(ctx), null);
            if (chosen != null) {
                UUID target = chosen;
                button(53, icon(Material.LIME_CONCRETE, "<green><bold>Send", "<gray>Hand it to the Postman"),
                        p -> send(plugin, ctx, recipient, target));
            } else {
                button(53, icon(Material.GRAY_CONCRETE, "<gray>Send", "<gray>Pick a mailbox first"), null);
            }
            fill();
        }
    }

    private static ItemStack sendingIcon(Context ctx) {
        ItemStack it = ctx.snapshot().clone();
        it.setAmount(1);
        return it;
    }

    private static void send(PostOfficePlugin plugin, Context ctx, UUID recipient, UUID mailboxId) {
        Player p = ctx.sender();
        p.closeInventory();
        PostBlock mailbox = plugin.blocks().byId(mailboxId);
        if (mailbox == null) {
            Msg.send(p, "send.mailbox-gone");
            return;
        }
        // The item must still be exactly where it was when the menu opened.
        ItemStack now = p.getInventory().getItem(ctx.slot());
        if (now == null || !now.isSimilar(ctx.snapshot())) {
            Msg.send(p, "send.item-moved");
            return;
        }
        ItemStack posted = MailItems.prepareForPost(now, p);
        if (now.getAmount() > 1) now.setAmount(now.getAmount() - 1);
        else p.getInventory().setItem(ctx.slot(), null);

        PostBlock d = ctx.dropoff();
        Delivery delivery = new Delivery(UUID.randomUUID(), p.getUniqueId(), p.getName(), recipient, mailboxId,
                ItemCodec.encode(posted), d.world, d.x, d.y, d.z, System.currentTimeMillis());
        plugin.post().post(delivery);
        p.playSound(p.getLocation(), Sound.ITEM_BUNDLE_INSERT, 1f, 1f);
        Msg.send(p, "send.posted", "player", PostBlocks.nameOf(recipient), "mailbox", mailbox.name,
                "item", MailItems.kind(posted) == MailKind.PARCEL ? "parcel" : "letter");
    }
}

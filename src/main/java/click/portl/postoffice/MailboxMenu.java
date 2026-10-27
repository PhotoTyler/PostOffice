package click.portl.postoffice;

import org.bukkit.Material;

/** Owner's sneak + right-click menu: rename, see how much mail is waiting, collect. */
final class MailboxMenu extends Menu {
    MailboxMenu(PostOfficePlugin plugin, PostBlock pb) {
        super(plugin, 3, Msg.get("mailbox.menu-title", "mailbox", pb.name));
        int count = plugin.store().mailCount(pb.id);
        button(11, icon(Material.NAME_TAG, "<yellow>Rename", "<gray>Now: <white>" + PostBlocks.escape(pb.name)), p -> {
            p.closeInventory();
            plugin.prompts().ask(p, "mailbox.rename-prompt", text -> {
                if (plugin.blocks().byId(pb.id) == null) return;
                plugin.blocks().rename(pb, text);
                plugin.blocks().updateHologram(pb);
                Msg.send(p, "mailbox.renamed", "mailbox", pb.name);
            });
        });
        button(13, icon(Displays.modelItem(pb.type.kind.id), "<white>" + PostBlocks.escape(pb.name),
                java.util.List.of(Msg.item("<gray>Waiting: <white>" + count),
                        Msg.item("<gray>" + pb.world + " " + pb.x + ", " + pb.y + ", " + pb.z))), null);
        button(15, icon(Material.CHEST, "<green>Collect mail", "<gray>" + count + " waiting"), p -> {
            p.closeInventory();
            plugin.blocks().collect(p, pb);
        });
        fill();
    }
}

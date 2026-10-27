package click.portl.postoffice;

import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * /postoffice items: a creative-style catalogue of every PostOffice item (the real creative tabs are
 * built into the client, so plugins can't add to them). Click = 1, shift-click = a full stack.
 */
final class ItemsMenu extends Menu {
    private static final List<MailKind> SHOWN = List.of(MailKind.LETTER, MailKind.EMPTY_BOX,
            MailKind.MAILBOX_UK, MailKind.MAILBOX_US, MailKind.DROPOFF);

    ItemsMenu(PostOfficePlugin plugin) {
        super(plugin, 3, Msg.get("command.items-title"));
        int slot = 11;
        for (MailKind kind : SHOWN) {
            ItemStack icon = item(kind);
            button(slot++, icon, null);
        }
        fill();
    }

    private static ItemStack item(MailKind kind) {
        return kind == MailKind.LETTER ? MailItems.blankLetter() : MailItems.create(kind);
    }

    @Override
    void click(Player p, int slot, boolean shift) {
        int index = slot - 11;
        if (index < 0 || index >= SHOWN.size()) return;
        MailKind kind = SHOWN.get(index);
        int amount = shift ? kind.maxStack : 1;
        for (int i = 0; i < amount; i++) LetterMenus.giveBack(p, item(kind));
    }
}

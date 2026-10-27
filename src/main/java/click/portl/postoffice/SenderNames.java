package click.portl.postoffice;

import java.time.Duration;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * "Who is this from?": the name shown on a parcel or letter. Asked in chat with a clickable
 * [Use my name] button, or the player types any name. Used by the drop-off send menu and the
 * Postmaster.
 */
final class SenderNames {
    private SenderNames() {}

    /** Asks for the name on the item with this unique id, then runs {@code after} once it's set. */
    static void ask(PostOfficePlugin plugin, Player p, String uniqueId, int hintSlot, String questionKey, Runnable after) {
        ClickCallback.Options once = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(2)).build();
        Component useMine = Msg.get("naming.use-my-name", "player", p.getName())
                .hoverEvent(HoverEvent.showText(Msg.get("naming.use-my-name-hover")))
                .clickEvent(ClickEvent.callback(audience -> Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.prompts().cancel(p);
                    apply(p, uniqueId, hintSlot, p.getName(), after);
                }), once));
        Component question = Msg.prefix().append(Msg.get(questionKey)).appendNewline()
                .append(useMine).append(Msg.get("naming.or-type"));
        plugin.prompts().ask(p, question, text -> apply(p, uniqueId, hintSlot, text, after));
    }

    private static void apply(Player p, String uniqueId, int hintSlot, String name, Runnable after) {
        int slot = MailItems.findSlot(p, uniqueId, hintSlot);
        if (slot < 0) {
            Msg.send(p, "naming.item-gone");
            return;
        }
        // "me" means the player's own in-game name
        String clean = name.trim().equalsIgnoreCase("me") ? p.getName() : PostBlocks.trimName(name);
        ItemStack item = p.getInventory().getItem(slot);
        MailItems.setSender(item, clean);
        Msg.send(p, "naming.set", "sender", clean);
        if (after != null) after.run();
    }
}

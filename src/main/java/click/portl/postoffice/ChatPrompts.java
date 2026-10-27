package click.portl.postoffice;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/** "Type the new name in chat" prompts. The answer is handled on the main thread. */
final class ChatPrompts implements Listener {
    private record Prompt(Consumer<String> handler, long expires) {}

    private final PostOfficePlugin plugin;
    private final Map<UUID, Prompt> pending = new ConcurrentHashMap<>();

    ChatPrompts(PostOfficePlugin plugin) {
        this.plugin = plugin;
    }

    void ask(Player p, String messageKey, Consumer<String> handler) {
        pending.put(p.getUniqueId(), new Prompt(handler, System.currentTimeMillis() + 60_000));
        Msg.send(p, messageKey);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Prompt prompt = pending.remove(e.getPlayer().getUniqueId());
        if (prompt == null || prompt.expires < System.currentTimeMillis()) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            if (text.equalsIgnoreCase("cancel") || text.isEmpty()) {
                Msg.send(p, "prompt.cancelled");
                return;
            }
            prompt.handler.accept(text);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        pending.remove(e.getPlayer().getUniqueId());
    }
}

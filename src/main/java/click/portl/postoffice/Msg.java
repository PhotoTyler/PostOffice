package click.portl.postoffice;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

/** Messages from config.yml (MiniMessage format). Placeholders are passed as key, value pairs. */
final class Msg {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static PostOfficePlugin plugin;

    private Msg() {}

    static void init(PostOfficePlugin p) {
        plugin = p;
    }

    static Component get(String key, String... placeholders) {
        String raw = plugin.getConfig().getString("messages." + key, "<red>Missing message: " + key);
        return parse(raw, placeholders);
    }

    static Component parse(String raw, String... placeholders) {
        TagResolver.Builder b = TagResolver.builder();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            b.resolver(Placeholder.unparsed(placeholders[i], placeholders[i + 1] == null ? "" : placeholders[i + 1]));
        }
        return MM.deserialize(raw, b.build());
    }

    /** Item names/lore: no default italics. */
    static Component item(String raw, String... placeholders) {
        return parse(raw, placeholders).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    static void send(CommandSender to, String key, String... placeholders) {
        String raw = plugin.getConfig().getString("messages." + key, "");
        if (raw.isEmpty()) return;
        to.sendMessage(prefix().append(parse(raw, placeholders)));
    }

    /** A line spoken by the Postman. */
    static void postman(CommandSender to, String raw, String... placeholders) {
        String name = plugin.getConfig().getString("postman.name", "Postman");
        to.sendMessage(parse(plugin.getConfig().getString("messages.postman-prefix", "<gold>[<name>]</gold> "), "name", name)
                .append(parse(raw, placeholders)));
    }

    static Component prefix() {
        return MM.deserialize(plugin.getConfig().getString("messages.prefix", ""));
    }
}

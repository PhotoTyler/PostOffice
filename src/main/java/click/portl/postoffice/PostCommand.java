package click.portl.postoffice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** /postoffice help | list | pack | give | reload | status */
final class PostCommand implements TabExecutor {
    private static final List<String> GIVEABLE = Arrays.stream(MailKind.values())
            .filter(k -> k != MailKind.SEALED_LETTER && k != MailKind.PARCEL).map(k -> k.id).toList();

    private final PostOfficePlugin plugin;

    PostCommand(PostOfficePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase();
        switch (sub) {
            case "list" -> {
                if (!(sender instanceof Player p)) return true;
                List<PostBlock> boxes = plugin.blocks().mailboxesOf(p.getUniqueId());
                if (boxes.isEmpty()) {
                    Msg.send(p, "command.no-mailboxes");
                    return true;
                }
                for (PostBlock pb : boxes) {
                    Msg.send(p, "command.list-line", "mailbox", pb.name, "count", String.valueOf(plugin.store().mailCount(pb.id)),
                            "location", pb.world + " " + pb.x + ", " + pb.y + ", " + pb.z);
                }
            }
            case "pack" -> {
                if (sender instanceof Player p) plugin.pack().send(p);
            }
            case "give" -> {
                if (!sender.hasPermission("postoffice.admin")) {
                    Msg.send(sender, "no-permission");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("/" + label + " give <" + String.join("|", GIVEABLE) + "> [player] [amount]");
                    return true;
                }
                MailKind kind = MailKind.byId(args[1].toLowerCase());
                if (kind == null || !GIVEABLE.contains(kind.id)) {
                    sender.sendMessage("Unknown item: " + args[1]);
                    return true;
                }
                Player target = args.length >= 3 ? Bukkit.getPlayerExact(args[2]) : (sender instanceof Player p ? p : null);
                if (target == null) {
                    sender.sendMessage("Player not found.");
                    return true;
                }
                int amount = 1;
                if (args.length >= 4) try {
                    amount = Math.max(1, Math.min(64, Integer.parseInt(args[3])));
                } catch (NumberFormatException ignored) {
                }
                for (int i = 0; i < amount; i++) {
                    ItemStack item = kind == MailKind.LETTER ? MailItems.blankLetter() : MailItems.create(kind);
                    LetterMenus.giveBack(target, item);
                }
                Msg.send(sender, "command.given", "item", MailItems.name(kind), "player", target.getName(), "count", String.valueOf(amount));
            }
            case "reload" -> {
                if (!sender.hasPermission("postoffice.admin")) {
                    Msg.send(sender, "no-permission");
                    return true;
                }
                plugin.reloadConfig();
                plugin.pack().reload();
                Msg.send(sender, "command.reloaded");
            }
            case "status" -> {
                if (!sender.hasPermission("postoffice.admin")) {
                    Msg.send(sender, "no-permission");
                    return true;
                }
                Msg.send(sender, "command.status", "postmen", String.valueOf(plugin.post().activePostmen()),
                        "queued", String.valueOf(plugin.post().queued()));
            }
            default -> {
                for (String line : plugin.getConfig().getStringList("messages.command.help")) sender.sendMessage(Msg.parse(line));
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("help", "list", "pack"));
            if (sender.hasPermission("postoffice.admin")) out.addAll(List.of("give", "reload", "status"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give") && sender.hasPermission("postoffice.admin")) {
            out.addAll(GIVEABLE);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase();
        out.removeIf(s -> !s.toLowerCase().startsWith(last));
        return out;
    }
}

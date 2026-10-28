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

/** /postoffice help | list | pack | items | give | reload | rebuild [here] | build | status */
final class PostCommand implements TabExecutor {
    /** Admin actions waiting for "/postoffice <action> confirm". */
    private record Pending(String action, long expires) {}

    private final java.util.Map<String, Pending> pending = new java.util.HashMap<>();

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
            case "items" -> {
                if (!(sender instanceof Player p)) return true;
                if (!p.hasPermission("postoffice.items") && p.getGameMode() != org.bukkit.GameMode.CREATIVE) {
                    Msg.send(p, "no-permission");
                    return true;
                }
                new ItemsMenu(plugin).open(p);
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
                // config.yml and messages only: nothing in the world is touched
                if (!sender.hasPermission("postoffice.admin")) {
                    Msg.send(sender, "no-permission");
                    return true;
                }
                plugin.reloadConfig();
                plugin.pack().reload();
                Msg.send(sender, "command.reloaded");
            }
            case "rebuild" -> {
                if (!sender.hasPermission("postoffice.admin")) {
                    Msg.send(sender, "no-permission");
                    return true;
                }
                boolean here = args.length >= 2 && args[1].equalsIgnoreCase("here");
                if (here && !(sender instanceof Player)) return true;
                String action = here ? "rebuild-here" : "rebuild";
                if (!confirmed(sender, args, action)) {
                    if (here) Msg.send(sender, "command.rebuild-here-confirm");
                    else Msg.send(sender, "command.rebuild-confirm", "boxes", String.valueOf(plugin.store().blocks.size()),
                            "offices", String.valueOf(plugin.store().offices.size()));
                    return true;
                }
                if (here) rebuildHere((Player) sender);
                else rebuildAll(sender);
            }
            case "build" -> {
                if (!sender.hasPermission("postoffice.admin")) {
                    Msg.send(sender, "no-permission");
                    return true;
                }
                if (!(sender instanceof Player p)) return true;
                // On a world that already has a Post Office it has to be confirmed, so it can't be run twice by accident.
                if (plugin.offices().hasAnyOffice() && !confirmed(sender, args, "build")) {
                    Msg.send(p, "command.build-confirm", "offices", String.valueOf(plugin.store().offices.size()));
                    return true;
                }
                plugin.offices().buildHere(p);
                Msg.send(p, "command.built");
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

    /**
     * True if this is the "confirm" for an action the sender asked for in the last 30 seconds.
     * Otherwise remembers the request (the caller then explains what confirming will do).
     */
    private boolean confirmed(CommandSender sender, String[] args, String action) {
        String who = sender.getName();
        boolean saysConfirm = args.length >= 2 && args[args.length - 1].equalsIgnoreCase("confirm");
        Pending p = pending.remove(who);
        if (saysConfirm && p != null && p.action().equals(action) && p.expires() > System.currentTimeMillis()) return true;
        pending.put(who, new Pending(action, System.currentTimeMillis() + 30_000));
        return false;
    }

    /** Every saved box and Postmaster removed and placed fresh in the same spot; mail is kept. */
    private void rebuildAll(CommandSender sender) {
        // First reconnect anything in loaded chunks whose save went missing, so it's rebuilt, not deleted
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            for (org.bukkit.entity.Entity e : w.getEntities()) {
                plugin.blocks().adopt(e);
                plugin.offices().adoptClerk(e);
            }
        }
        int boxes = 0, waiting = 0, removed = 0;
        for (PostBlock pb : new ArrayList<>(plugin.store().blocks.values())) {
            if (pb.isLoaded()) {
                removed += plugin.blocks().rebuild(pb);
                boxes++;
            } else {
                waiting++; // redrawn fresh when its chunk loads
            }
        }
        removed += plugin.blocks().removeOrphans();
        int[] clerks = plugin.offices().rebuildClerks();
        Msg.send(sender, "command.rebuild-done", "boxes", String.valueOf(boxes), "postmasters", String.valueOf(clerks[0]),
                "waiting", String.valueOf(waiting + clerks[1]), "removed", String.valueOf(removed));
    }

    /** The one box the player is looking at. */
    private void rebuildHere(Player p) {
        PostBlock pb = null;
        org.bukkit.entity.Entity target = p.getTargetEntity(6);
        if (target != null) {
            String id = Displays.tagOf(target, Keys.BLOCK_ID);
            if (id != null) try {
                pb = plugin.blocks().any(java.util.UUID.fromString(id));
            } catch (IllegalArgumentException ignored) {
            }
            if (pb == null) pb = plugin.blocks().adopt(target);
        }
        if (pb == null) pb = plugin.blocks().at(p.getTargetBlockExact(6));
        if (pb == null) {
            Msg.send(p, "command.no-box-here");
            return;
        }
        int removed = plugin.blocks().rebuild(pb);
        Msg.send(p, "command.rebuilt-here", "removed", String.valueOf(removed));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("help", "list", "pack", "items"));
            if (sender.hasPermission("postoffice.admin")) out.addAll(List.of("give", "reload", "status", "rebuild", "build"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give") && sender.hasPermission("postoffice.admin")) {
            out.addAll(GIVEABLE);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("rebuild") && sender.hasPermission("postoffice.admin")) {
            out.addAll(List.of("here", "confirm"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("build") && sender.hasPermission("postoffice.admin")) {
            out.add("confirm");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("rebuild") && args[1].equalsIgnoreCase("here")) {
            out.add("confirm");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase();
        out.removeIf(s -> !s.toLowerCase().startsWith(last));
        return out;
    }
}

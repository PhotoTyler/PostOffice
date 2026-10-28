package click.portl.mysterymail;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * PostOffice add-on: now and then the Postman brings each player a letter with a joke in it from a
 * random mystery sender, and once in a while a mystery parcel with a random item.
 */
public final class MysteryMail extends JavaPlugin implements Listener {
    private static final int PAGE_CHARS = 220; // roughly what fits on one book page

    private Jokes jokes;
    private PostOfficeBridge post;
    private static final NamespacedKey POSTMAN_TAG = new NamespacedKey("postoffice", "postman");
    private record Coming(Location box, long expires) {}
    private final Map<UUID, Coming> coming = new HashMap<>(); // mystery mail on its way: player -> their mailbox
    private File dataFile;
    private final Map<UUID, Long> nextMail = new HashMap<>(); // real-time mode: when each player's next letter is due
    private final Map<String, Long> lastDay = new HashMap<>(); // minecraft-day mode: last day seen per world

    @Override
    public void onEnable() {
        saveDefaultConfig();
        jokes = new Jokes(this);
        post = PostOfficeBridge.connect(this);
        if (post == null) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        dataFile = new File(getDataFolder(), "data.yml");
        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        for (String key : data.getKeys(false)) {
            try {
                nextMail.put(UUID.fromString(key), data.getLong(key));
            } catch (IllegalArgumentException ignored) {
            }
        }
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new Burning(this, post), this);
        getServer().getPluginManager().registerEvents(new Ashes(this), this);
        Bukkit.removeRecipe(Burning.OLD_CHARCOAL_RECIPE);
        for (var r : Burning.ashRecipes()) Bukkit.addRecipe(r);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 200L, 100L);
        Bukkit.getScheduler().runTaskTimer(this, this::checkComing, 20L, 10L);
    }

    @Override
    public void onDisable() {
        Bukkit.removeRecipe(Burning.LETTERS_RECIPE);
        Bukkit.removeRecipe(Burning.BOXES_RECIPE);
        if (post != null) save();
    }

    // ---------------- schedule ----------------

    private void tick() {
        if (getConfig().getString("schedule.mode", "real-time").equalsIgnoreCase("minecraft-day")) {
            for (World w : Bukkit.getWorlds()) {
                if (!worldAllowed(w)) continue;
                long day = w.getFullTime() / 24000L;
                Long last = lastDay.put(w.getName(), day);
                if (last == null || day <= last) continue;
                // A new morning: everyone in this world gets a letter
                for (Player p : w.getPlayers()) maybeSend(p);
            }
            return;
        }
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Player p : Bukkit.getOnlinePlayers()) {
            Long due = nextMail.get(p.getUniqueId());
            if (due == null) {
                nextMail.put(p.getUniqueId(), now + interval());
                changed = true;
            } else if (now >= due) {
                nextMail.put(p.getUniqueId(), now + interval());
                changed = true;
                if (worldAllowed(p.getWorld())) maybeSend(p);
            }
        }
        if (changed) save();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // New players wait one full interval for their first letter.
        nextMail.putIfAbsent(e.getPlayer().getUniqueId(), System.currentTimeMillis() + interval());
    }

    private long interval() {
        double base = getConfig().getDouble("schedule.interval-minutes", 30);
        double extra = getConfig().getDouble("schedule.random-extra-minutes", 15);
        double minutes = Math.max(1, base + (extra > 0 ? ThreadLocalRandom.current().nextDouble(extra) : 0));
        return (long) (minutes * 60_000);
    }

    private boolean worldAllowed(World w) {
        List<String> worlds = getConfig().getStringList("schedule.worlds");
        return worlds.isEmpty() || worlds.contains(w.getName());
    }

    private void maybeSend(Player p) {
        if (!p.hasPermission("mysterymail.receive")) return;
        if (!post.hasMailbox(p.getUniqueId())) return;
        if (ThreadLocalRandom.current().nextDouble() >= getConfig().getDouble("schedule.chance", 1.0)) return;
        boolean parcel = ThreadLocalRandom.current().nextDouble() < getConfig().getDouble("parcel-chance", 0.08);
        send(p, parcel);
    }

    // ---------------- sending ----------------

    /** Hands a mystery letter (joke) or parcel to the Postman for this player. */
    private void send(Player p, boolean parcel) {
        UUID id = p.getUniqueId();
        if (parcel) {
            String from = getConfig().getString("parcel.sender", "A Mystery Sender");
            post(id, from, post.parcel(from, List.of(randomLoot())));
            return;
        }
        String name = p.getName();
        String from = randomSender();
        jokes.next().thenAccept(joke -> Bukkit.getScheduler().runTask(this, () ->
                post(id, from, post.letter(from, pages(name, from, joke)))));
    }

    private void post(UUID player, String from, ItemStack mail) {
        Location box = post.deliver(player, from, mail);
        if (box != null) coming.put(player, new Coming(box, System.currentTimeMillis() + 15 * 60_000L));
    }

    /** Rings the bell once the Postman is close to the mailbox, not when he sets off. */
    private void checkComing() {
        if (coming.isEmpty()) return;
        double r = getConfig().getDouble("bell-distance", 10);
        long now = System.currentTimeMillis();
        coming.entrySet().removeIf(e -> {
            Coming c = e.getValue();
            if (now > c.expires() || !c.box().isWorldLoaded()) return true;
            if (!c.box().isChunkLoaded()) return false;
            for (Villager v : c.box().getNearbyEntitiesByType(Villager.class, r)) {
                if (!v.getPersistentDataContainer().has(POSTMAN_TAG)) continue;
                Player p = Bukkit.getPlayer(e.getKey());
                if (p != null) {
                    msg(p, "on-the-way");
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.8f);
                }
                return true;
            }
            return false;
        });
    }

    private List<String> pages(String player, String sender, Jokes.Joke joke) {
        List<String> pages = new ArrayList<>();
        if (joke.punchline() == null) {
            pages.addAll(fit(fill(getConfig().getString("letter.single", "<setup>"), player, joke, sender)));
        } else {
            pages.addAll(fit(fill(getConfig().getString("letter.page1", "<setup>"), player, joke, sender)));
            pages.addAll(fit(fill(getConfig().getString("letter.page2", "<punchline>"), player, joke, sender)));
        }
        return pages;
    }

    private final List<String> senderBag = new ArrayList<>();
    private String lastSender;

    /**
     * A different sender every time: the names from the config plus every "A <Sneaky> <Llama>"
     * mix, shuffled, and each used once before any comes round again.
     */
    private String randomSender() {
        if (senderBag.isEmpty()) {
            java.util.Set<String> all = new java.util.LinkedHashSet<>(getConfig().getStringList("senders"));
            for (String adj : getConfig().getStringList("sender-parts.describing")) {
                for (String who : getConfig().getStringList("sender-parts.who")) all.add(article(adj) + " " + adj + " " + who);
            }
            if (all.isEmpty()) all.add("A Secret Admirer");
            senderBag.addAll(all);
            java.util.Collections.shuffle(senderBag);
            // never the same name twice in a row, even across a reshuffle
            if (senderBag.size() > 1 && senderBag.getLast().equals(lastSender)) java.util.Collections.swap(senderBag, 0, senderBag.size() - 1);
        }
        lastSender = senderBag.removeLast();
        return lastSender;
    }

    private static String article(String word) {
        return "AEIOU".indexOf(Character.toUpperCase(word.charAt(0))) >= 0 ? "An" : "A";
    }

    private static String fill(String template, String player, Jokes.Joke joke, String sender) {
        return template.replace("\\n", "\n").replace("<player>", player).replace("<sender>", sender)
                .replace("<setup>", joke.setup()).replace("<punchline>", joke.punchline() == null ? "" : joke.punchline());
    }

    /** Long jokes spill over onto extra pages instead of running off the bottom. */
    private static List<String> fit(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder page = new StringBuilder();
        for (String word : text.split("(?<=[ \n])")) {
            if (page.length() + word.length() > PAGE_CHARS && !page.isEmpty()) {
                out.add(page.toString().strip());
                page.setLength(0);
            }
            page.append(word);
        }
        if (!page.isEmpty() || out.isEmpty()) out.add(page.toString().strip());
        return out;
    }

    private ItemStack randomLoot() {
        List<Map<?, ?>> loot = getConfig().getMapList("parcel.loot");
        int total = 0;
        for (Map<?, ?> m : loot) total += weight(m);
        if (total > 0) {
            int roll = ThreadLocalRandom.current().nextInt(total);
            for (Map<?, ?> m : loot) {
                roll -= weight(m);
                if (roll >= 0) continue;
                Material mat = Material.matchMaterial(String.valueOf(m.get("item")));
                if (mat == null || !mat.isItem()) break;
                int min = Math.max(1, num(m.get("min"), 1));
                int max = Math.max(min, num(m.get("max"), min));
                int amount = Math.min(mat.getMaxStackSize(), ThreadLocalRandom.current().nextInt(min, max + 1));
                return new ItemStack(mat, amount);
            }
        }
        return new ItemStack(Material.COOKIE, 8);
    }

    private static int weight(Map<?, ?> m) {
        return Math.max(0, num(m.get("weight"), 1));
    }

    private static int num(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }

    // ---------------- command ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            senderBag.clear();
            msg(sender, "reloaded");
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("joke")) {
            jokes.next().thenAccept(j -> Bukkit.getScheduler().runTask(this, () -> {
                sender.sendMessage(Component.text(j.setup()));
                if (j.punchline() != null) sender.sendMessage(Component.text(j.punchline()));
            }));
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("send")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                msg(sender, "not-found", "player", args[1]);
                return true;
            }
            if (!post.hasMailbox(target.getUniqueId())) {
                msg(sender, "no-mailbox", "player", target.getName());
                return true;
            }
            boolean parcel = args.length >= 3 && args[2].equalsIgnoreCase("parcel");
            send(target, parcel);
            msg(sender, "sent", "kind", parcel ? "parcel" : "letter", "player", target.getName());
            return true;
        }
        msg(sender, "usage");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("send", "joke", "reload");
        if (args.length == 2 && args[0].equalsIgnoreCase("send")) return null; // player names
        if (args.length == 3 && args[0].equalsIgnoreCase("send")) return List.of("letter", "parcel");
        return List.of();
    }

    // ---------------- misc ----------------

    void msg(CommandSender to, String key, String... placeholders) {
        // getString(path) falls back to the built-in default when an older config.yml lacks the message
        String text = getConfig().getString("messages." + key);
        if (text == null) text = "";
        if (text.isBlank()) return;
        TagResolver.Builder tags = TagResolver.builder();
        for (int i = 0; i + 1 < placeholders.length; i += 2) tags.resolver(Placeholder.unparsed(placeholders[i], placeholders[i + 1]));
        to.sendMessage(MiniMessage.miniMessage().deserialize(text, tags.build()));
    }

    private void save() {
        YamlConfiguration data = new YamlConfiguration();
        nextMail.forEach((id, t) -> data.set(id.toString(), t));
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Couldn't save data.yml: " + e.getMessage());
        }
    }
}

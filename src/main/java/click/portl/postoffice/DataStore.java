package click.portl.postoffice;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Plain YAML storage in plugins/PostOffice/data.
 * <ul>
 *   <li>blocks.yml   - every mailbox and drop-off</li>
 *   <li>mail/&lt;mailbox id&gt;.yml - the mailbox contents: an unlimited list of Base64 items</li>
 *   <li>queue.yml    - mail posted but not yet delivered (survives restarts)</li>
 *   <li>returns.yml  - undeliverable mail waiting to be handed back to its sender</li>
 *   <li>parcels.yml  - parcels placed on the ground</li>
 *   <li>villages.yml - Post Offices, and every village already handled</li>
 *   <li>state.yml    - whether this is a fresh install (only then are Post Offices built by themselves)</li>
 * </ul>
 * Ids are fixed UUIDs (and village ids) that never change between versions.
 * Files are built on the main thread and written on one background thread, in order, via a temp file.
 */
final class DataStore {
    private final PostOfficePlugin plugin;
    private final File dir;
    private final File mailDir;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PostOffice-IO");
        t.setDaemon(true);
        return t;
    });

    final Map<UUID, PostBlock> blocks = new LinkedHashMap<>();
    final Map<UUID, List<String>> mail = new HashMap<>();
    final Map<UUID, Delivery> deliveries = new LinkedHashMap<>();
    final Map<UUID, List<String>> returns = new HashMap<>();
    final Map<UUID, PlacedParcel> parcels = new LinkedHashMap<>();
    final java.util.Set<String> villagesDone = new java.util.HashSet<>();
    final Map<String, PostOffices.Office> offices = new LinkedHashMap<>();
    final Map<String, Long> clerkDeaths = new HashMap<>(); // village -> when its Postmaster was killed
    /** True only on a brand-new install, until the first Post Office has been built. */
    boolean firstInstall;

    DataStore(PostOfficePlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "data");
        this.mailDir = new File(dir, "mail");
        mailDir.mkdirs();
    }

    // ---------------- load ----------------

    /** Call before anything else is created in the data folder. */
    void loadState(boolean freshFolder) {
        File f = new File(dir, "state.yml");
        if (f.exists()) {
            firstInstall = YamlConfiguration.loadConfiguration(f).getBoolean("first-install", false);
        } else {
            firstInstall = freshFolder;
            saveState();
        }
    }

    void saveState() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("first-install", firstInstall);
        write(new File(dir, "state.yml"), y.saveToString());
    }

    /** Checks the data folder can actually be written, and says so loudly if not. */
    void checkWritable() {
        io.execute(() -> {
            File test = new File(dir, ".write-test");
            try {
                Files.writeString(test.toPath(), "ok", StandardCharsets.UTF_8);
                Files.deleteIfExists(test.toPath());
            } catch (IOException e) {
                plugin.getLogger().severe("CAN'T SAVE to " + dir + " (" + e + "). Mailboxes, mail and Post Offices "
                        + "will be forgotten on restart. Check the folder's permissions / disk space.");
            }
        });
    }

    void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new File(dir, "blocks.yml"));
        ConfigurationSection s = y.getConfigurationSection("blocks");
        if (s != null) for (String key : s.getKeys(false)) {
            ConfigurationSection b = s.getConfigurationSection(key);
            try {
                PostBlock.Type type = PostBlock.Type.valueOf(b.getString("type"));
                blocks.put(UUID.fromString(key), new PostBlock(UUID.fromString(key), type,
                        UUID.fromString(b.getString("owner")), b.getString("name", ""), b.getString("world"),
                        b.getInt("x"), b.getInt("y"), b.getInt("z"), (float) b.getDouble("yaw")));
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Skipping broken block " + key + ": " + e);
            }
        }

        File[] files = mailDir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files != null) for (File f : files) {
            try {
                UUID id = UUID.fromString(f.getName().substring(0, f.getName().length() - 4));
                mail.put(id, new ArrayList<>(YamlConfiguration.loadConfiguration(f).getStringList("items")));
            } catch (IllegalArgumentException ignored) {
            }
        }

        y = YamlConfiguration.loadConfiguration(new File(dir, "queue.yml"));
        s = y.getConfigurationSection("deliveries");
        if (s != null) for (String key : s.getKeys(false)) {
            ConfigurationSection d = s.getConfigurationSection(key);
            try {
                Delivery del = new Delivery(UUID.fromString(key), UUID.fromString(d.getString("sender")),
                        d.getString("sender-name"), UUID.fromString(d.getString("recipient")),
                        UUID.fromString(d.getString("mailbox")), d.getString("item"), d.getString("world"),
                        d.getInt("x"), d.getInt("y"), d.getInt("z"), d.getLong("created"));
                del.returning = d.getBoolean("returning");
                deliveries.put(del.id, del);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Skipping broken delivery " + key + ": " + e);
            }
        }

        y = YamlConfiguration.loadConfiguration(new File(dir, "returns.yml"));
        for (String key : y.getKeys(false)) {
            try {
                returns.put(UUID.fromString(key), new ArrayList<>(y.getStringList(key)));
            } catch (IllegalArgumentException ignored) {
            }
        }

        loadVillages();

        y = YamlConfiguration.loadConfiguration(new File(dir, "parcels.yml"));
        s = y.getConfigurationSection("parcels");
        if (s != null) for (String key : s.getKeys(false)) {
            ConfigurationSection p = s.getConfigurationSection(key);
            try {
                List<String> contents = p.contains("contents") ? p.getStringList("contents")
                        : (p.getString("payload") == null ? List.of() : List.of(p.getString("payload")));
                parcels.put(UUID.fromString(key), new PlacedParcel(UUID.fromString(key), p.getString("world"),
                        p.getInt("x"), p.getInt("y"), p.getInt("z"), (float) p.getDouble("yaw"),
                        contents, p.getBoolean("open")));
                parcels.get(UUID.fromString(key)).sender = p.getString("sender");
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Skipping broken parcel " + key + ": " + e);
            }
        }
    }

    void loadVillages() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new File(dir, "villages.yml"));
        villagesDone.addAll(y.getStringList("done"));
        ConfigurationSection deaths = y.getConfigurationSection("postmaster-deaths");
        if (deaths != null) for (String k : deaths.getKeys(false)) clerkDeaths.put(deaths.getString(k + ".village"), deaths.getLong(k + ".time"));
        ConfigurationSection s = y.getConfigurationSection("offices");
        if (s != null) for (String key : s.getKeys(false)) {
            ConfigurationSection o = s.getConfigurationSection(key);
            offices.put(o.getString("village"), new PostOffices.Office(o.getString("village"), o.getString("world"),
                    o.getDouble("x"), o.getDouble("y"), o.getDouble("z"), (float) o.getDouble("yaw")));
        }
    }

    void saveVillages() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("done", new ArrayList<>(villagesDone));
        int d = 0;
        for (Map.Entry<String, Long> en : clerkDeaths.entrySet()) {
            y.set("postmaster-deaths." + d + ".village", en.getKey());
            y.set("postmaster-deaths." + (d++) + ".time", en.getValue());
        }
        int i = 0;
        for (PostOffices.Office o : offices.values()) {
            String p = "offices." + (i++) + ".";
            y.set(p + "village", o.village());
            y.set(p + "world", o.world());
            y.set(p + "x", o.x());
            y.set(p + "y", o.y());
            y.set(p + "z", o.z());
            y.set(p + "yaw", o.yaw());
        }
        write(new File(dir, "villages.yml"), y.saveToString());
    }

    // ---------------- mail helpers ----------------

    List<String> mail(UUID mailbox) {
        return mail.computeIfAbsent(mailbox, k -> new ArrayList<>());
    }

    int mailCount(UUID mailbox) {
        List<String> l = mail.get(mailbox);
        return l == null ? 0 : l.size();
    }

    void addMail(UUID mailbox, String item) {
        mail(mailbox).add(item);
        saveMail(mailbox);
    }

    void addReturn(UUID player, String item) {
        returns.computeIfAbsent(player, k -> new ArrayList<>()).add(item);
        saveReturns();
    }

    // ---------------- save ----------------

    void saveBlocks() {
        YamlConfiguration y = new YamlConfiguration();
        for (PostBlock b : blocks.values()) {
            String p = "blocks." + b.id + ".";
            y.set(p + "type", b.type.name());
            y.set(p + "owner", b.owner.toString());
            y.set(p + "name", b.name);
            y.set(p + "world", b.world);
            y.set(p + "x", b.x);
            y.set(p + "y", b.y);
            y.set(p + "z", b.z);
            y.set(p + "yaw", b.yaw);
        }
        write(new File(dir, "blocks.yml"), y.saveToString());
    }

    void saveMail(UUID mailbox) {
        File f = new File(mailDir, mailbox + ".yml");
        List<String> items = mail.get(mailbox);
        if (items == null || items.isEmpty()) {
            delete(f);
            return;
        }
        YamlConfiguration y = new YamlConfiguration();
        y.set("items", new ArrayList<>(items));
        write(f, y.saveToString());
    }

    void deleteMail(UUID mailbox) {
        mail.remove(mailbox);
        delete(new File(mailDir, mailbox + ".yml"));
    }

    void saveQueue() {
        YamlConfiguration y = new YamlConfiguration();
        for (Delivery d : deliveries.values()) {
            String p = "deliveries." + d.id + ".";
            y.set(p + "sender", d.sender.toString());
            y.set(p + "sender-name", d.senderName);
            y.set(p + "recipient", d.recipient.toString());
            y.set(p + "mailbox", d.mailbox.toString());
            y.set(p + "item", d.item);
            y.set(p + "world", d.world);
            y.set(p + "x", d.x);
            y.set(p + "y", d.y);
            y.set(p + "z", d.z);
            y.set(p + "created", d.created);
            y.set(p + "returning", d.returning);
        }
        write(new File(dir, "queue.yml"), y.saveToString());
    }

    void saveReturns() {
        YamlConfiguration y = new YamlConfiguration();
        returns.forEach((k, v) -> {
            if (!v.isEmpty()) y.set(k.toString(), new ArrayList<>(v));
        });
        write(new File(dir, "returns.yml"), y.saveToString());
    }

    void saveParcels() {
        YamlConfiguration y = new YamlConfiguration();
        for (PlacedParcel p : parcels.values()) {
            String k = "parcels." + p.id + ".";
            y.set(k + "world", p.world);
            y.set(k + "x", p.x);
            y.set(k + "y", p.y);
            y.set(k + "z", p.z);
            y.set(k + "yaw", p.yaw);
            y.set(k + "contents", new ArrayList<>(p.contents));
            y.set(k + "sender", p.sender);
            y.set(k + "open", p.open);
        }
        write(new File(dir, "parcels.yml"), y.saveToString());
    }

    private void write(File file, String content) {
        io.execute(() -> {
            try {
                File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
                Files.writeString(tmp.toPath(), content, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicFailed) {
                    // Some hosts' file systems can't do an atomic rename: fall back to a plain one,
                    // and if even that fails, write the file directly.
                    try {
                        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException moveFailed) {
                        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
                        Files.deleteIfExists(tmp.toPath());
                    }
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not save " + file.getName(), e);
            }
        });
    }

    private void delete(File file) {
        io.execute(() -> file.delete());
    }

    /** Waits for every pending write (on disable). */
    void close() {
        io.shutdown();
        try {
            io.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}

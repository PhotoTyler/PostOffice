package click.portl.postoffice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

/**
 * The sorting office. Posted mail waits in the queue at its drop-off for a few seconds (so several
 * letters go out together), then a Postman is sent out carrying everything from that drop-off.
 */
final class PostService {
    private final PostOfficePlugin plugin;
    private final DataStore store;
    private final Map<UUID, Postman> postmen = new HashMap<>();
    private BukkitTask task;
    private long ticks;

    PostService(PostOfficePlugin plugin, DataStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 1L);
    }

    void stop() {
        if (task != null) task.cancel();
        for (Postman p : new ArrayList<>(postmen.values())) p.despawnQuietly();
        postmen.clear();
        for (Delivery d : store.deliveries.values()) d.assigned = false;
    }

    int activePostmen() {
        return postmen.size();
    }

    int queued() {
        return store.deliveries.size();
    }

    void post(Delivery d) {
        store.deliveries.put(d.id, d);
        store.saveQueue();
    }

    Postman postmanOf(Entity e) {
        String id = e.getPersistentDataContainer().get(Keys.POSTMAN, PersistentDataType.STRING);
        if (id == null) return null;
        try {
            return postmen.get(UUID.fromString(id));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    boolean isPostmanEntity(Entity e) {
        return e.getPersistentDataContainer().has(Keys.POSTMAN);
    }

    private void tick() {
        ticks++;
        if (ticks % 20 == 0) dispatch();
        for (Postman p : new ArrayList<>(postmen.values())) p.tick();
    }

    /** Sends a Postman out for every drop-off whose oldest waiting mail has waited long enough. */
    private void dispatch() {
        int max = plugin.getConfig().getInt("postman.max-active", 10);
        long delay = plugin.getConfig().getLong("postman.dispatch-delay-seconds", 8) * 1000L;
        long now = System.currentTimeMillis();
        Map<String, List<Delivery>> byOrigin = new LinkedHashMap<>();
        for (Delivery d : store.deliveries.values()) {
            if (!d.assigned) byOrigin.computeIfAbsent(d.originKey(), k -> new ArrayList<>()).add(d);
        }
        for (List<Delivery> group : byOrigin.values()) {
            if (postmen.size() >= max) return;
            long oldest = group.stream().mapToLong(d -> d.created).min().orElse(now);
            if (now - oldest < delay) continue;
            Location origin = group.getFirst().origin();
            if (origin == null) {
                // The drop-off's world is gone: deliver straight away.
                for (Delivery d : group) deliver(d);
                continue;
            }
            Postman p = new Postman(plugin, this, group);
            group.forEach(d -> d.assigned = true);
            postmen.put(p.id, p);
            p.start(origin);
        }
    }

    void finished(Postman p) {
        postmen.remove(p.id);
    }

    /** Works out which mailbox a delivery should go to now. Null = return to the sender's pocket. */
    PostBlock resolveTarget(Delivery d) {
        PostBlock box = plugin.blocks().byId(d.mailbox);
        if (box != null && box.owner.equals(d.recipient)) return box;
        List<PostBlock> others = plugin.blocks().mailboxesOf(d.recipient);
        if (!others.isEmpty()) {
            d.mailbox = others.getFirst().id;
            store.saveQueue();
            return others.getFirst();
        }
        if (!d.returning) {
            d.returning = true;
            Player s = Bukkit.getPlayer(d.sender);
            if (s != null && !d.sender.equals(d.recipient)) Msg.send(s, "send.returning", "player", PostBlocks.nameOf(d.recipient));
            d.recipient = d.sender;
            List<PostBlock> mine = plugin.blocks().mailboxesOf(d.sender);
            if (!mine.isEmpty()) {
                d.mailbox = mine.getFirst().id;
                store.saveQueue();
                return mine.getFirst();
            }
        }
        return null;
    }

    /** Puts the item into its mailbox (or the sender's returns) and tells everyone. */
    void deliver(Delivery d) {
        store.deliveries.remove(d.id);
        store.saveQueue();
        PostBlock box = resolveTarget(d);
        if (box == null) {
            store.addReturn(d.sender, d.item);
            Player s = Bukkit.getPlayer(d.sender);
            if (s != null) giveReturns(s);
            return;
        }
        store.addMail(box.id, d.item);
        plugin.blocks().updateHologram(box);

        Player owner = Bukkit.getPlayer(box.owner);
        if (owner != null) {
            Msg.postman(owner, plugin.getConfig().getString("messages.postman.delivered"),
                    "player", owner.getName(), "mailbox", box.name);
            owner.playSound(owner.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.4f);
        }
        Player sender = Bukkit.getPlayer(d.sender);
        if (sender != null && !d.sender.equals(box.owner) && !d.returning) {
            Msg.send(sender, "send.delivered", "player", PostBlocks.nameOf(box.owner), "mailbox", box.name);
        }
    }

    /** The Postman was killed: his bag spills on the floor. */
    List<ItemStack> spill(Postman p, Player killer) {
        List<ItemStack> drops = new ArrayList<>();
        for (Delivery d : p.bag()) {
            store.deliveries.remove(d.id);
            ItemStack item = ItemCodec.decode(d.item);
            if (item != null) drops.add(item);
            Player s = Bukkit.getPlayer(d.sender);
            if (s != null && !d.sender.equals(d.recipient)) Msg.send(s, "send.stolen", "player", PostBlocks.nameOf(d.recipient));
        }
        store.saveQueue();
        p.bag().clear();
        postmen.remove(p.id);
        return drops;
    }

    /** Hands back undeliverable mail. */
    void giveReturns(Player p) {
        List<String> items = store.returns.remove(p.getUniqueId());
        if (items == null || items.isEmpty()) return;
        for (String data : items) LetterMenus.giveBack(p, ItemCodec.decode(data));
        store.saveReturns();
        Msg.send(p, "send.returned", "count", String.valueOf(items.size()));
    }
}

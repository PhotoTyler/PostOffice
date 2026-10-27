package click.portl.postoffice;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/** Mailboxes and drop-offs in the world: placing, breaking, collecting mail, the "New Mail" hologram. */
final class PostBlocks {
    private final PostOfficePlugin plugin;
    private final DataStore store;
    private final Map<BlockKey, PostBlock> byLoc = new HashMap<>();

    PostBlocks(PostOfficePlugin plugin, DataStore store) {
        this.plugin = plugin;
        this.store = store;
        for (PostBlock b : store.blocks.values()) index(b);
    }

    private void index(PostBlock b) {
        for (int i = 0; i < b.height(); i++) byLoc.put(new BlockKey(b.world, b.x, b.y + i, b.z), b);
    }

    private void unindex(PostBlock b) {
        for (int i = 0; i < b.height(); i++) byLoc.remove(new BlockKey(b.world, b.x, b.y + i, b.z));
    }

    PostBlock at(Block b) {
        return b == null ? null : byLoc.get(BlockKey.of(b));
    }

    /** Any box (mailbox or drop-off) by id. */
    PostBlock any(UUID id) {
        return id == null ? null : store.blocks.get(id);
    }

    PostBlock byId(UUID id) {
        PostBlock b = id == null ? null : store.blocks.get(id);
        return b != null && b.type.isMailbox() ? b : null;
    }

    List<PostBlock> mailboxesOf(UUID owner) {
        List<PostBlock> out = new ArrayList<>();
        for (PostBlock b : store.blocks.values()) if (b.type.isMailbox() && b.owner.equals(owner)) out.add(b);
        out.sort(Comparator.comparing(b -> b.name.toLowerCase()));
        return out;
    }

    /** Everyone who owns at least one mailbox, online players first. */
    List<UUID> registeredPlayers() {
        Set<UUID> owners = new LinkedHashSet<>();
        for (PostBlock b : store.blocks.values()) if (b.type.isMailbox()) owners.add(b.owner);
        List<UUID> list = new ArrayList<>(owners);
        list.sort(Comparator.<UUID, Boolean>comparing(u -> Bukkit.getPlayer(u) == null).thenComparing(u -> nameOf(u).toLowerCase()));
        return list;
    }

    static String nameOf(UUID player) {
        OfflinePlayer p = Bukkit.getOfflinePlayer(player);
        return p.getName() == null ? "Unknown" : p.getName();
    }

    Iterable<PostBlock> all() {
        return store.blocks.values();
    }

    // ---------------- placing / breaking ----------------

    static float snapYaw(float playerYaw) {
        return Math.round((playerYaw + 180f) / 90f) * 90f % 360f;
    }

    /** Where a block-like item goes when the player right-clicks a face. Null if it can't go there. */
    static Block placeTarget(Block clicked, BlockFace face) {
        Block target = clicked.isReplaceable() ? clicked : clicked.getRelative(face);
        if (!target.isReplaceable()) return null;
        if (target.getY() < target.getWorld().getMinHeight() || target.getY() >= target.getWorld().getMaxHeight()) return null;
        for (Entity e : target.getWorld().getNearbyEntities(BoundingBox.of(target))) {
            if (e instanceof LivingEntity) return null;
        }
        return target;
    }

    /** Puts a barrier down and asks other plugins (claims, WorldGuard) whether that's allowed. */
    static boolean claimBlock(Player p, Block target, Block against, ItemStack hand, EquipmentSlot slot) {
        BlockState previous = target.getState();
        target.setType(Material.BARRIER, false);
        BlockPlaceEvent ev = new BlockPlaceEvent(target, previous, against, hand, p, true, slot);
        Bukkit.getPluginManager().callEvent(ev);
        if (ev.isCancelled() || !ev.canBuild()) {
            previous.update(true, false);
            return false;
        }
        return true;
    }

    boolean place(Player p, Block clicked, BlockFace face, ItemStack hand, EquipmentSlot slot) {
        MailKind kind = MailItems.kind(hand);
        PostBlock.Type type = PostBlock.Type.of(kind);
        if (type == null) return false;
        String perm = type.isMailbox() ? "postoffice.place.mailbox" : "postoffice.place.dropoff";
        if (!p.hasPermission(perm)) {
            Msg.send(p, "no-permission");
            return false;
        }
        Block target = placeTarget(clicked, face);
        if (target == null) return false;
        if (type == PostBlock.Type.DROPOFF && !target.getRelative(BlockFace.UP).isReplaceable()) {
            Msg.send(p, "dropoff.no-room");
            return false;
        }
        if (!claimBlock(p, target, clicked, hand, slot)) return false;
        target.setType(Material.AIR, false); // the shaped hitbox does the colliding

        String name;
        if (hand.getItemMeta().hasCustomName()) {
            name = PlainTextComponentSerializer.plainText().serialize(hand.getItemMeta().customName());
        } else if (type.isMailbox()) {
            name =PlainTextComponentSerializer.plainText().serialize(Msg.parse(
                    plugin.getConfig().getString("mailbox.default-name", "Mailbox <number>"),
                    "number", String.valueOf(mailboxesOf(p.getUniqueId()).size() + 1)));
        } else {
            name = "Drop-off";
        }
        name = trimName(name);

        PostBlock pb = new PostBlock(UUID.randomUUID(), type, p.getUniqueId(), name, target.getWorld().getName(),
                target.getX(), target.getY(), target.getZ(), snapYaw(p.getYaw()));
        store.blocks.put(pb.id, pb);
        index(pb);
        store.saveBlocks();
        refresh(pb);

        if (p.getGameMode() != org.bukkit.GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
        target.getWorld().playSound(pb.center(), Sound.BLOCK_METAL_PLACE, 1f, 1f);
        if (type.isMailbox()) Msg.send(p, "mailbox.placed", "mailbox", pb.name);
        else Msg.send(p, "dropoff.placed");
        return true;
    }

    /** A box the plugin builds itself (village Post Offices). */
    PostBlock placeSystem(PostBlock.Type type, Block target, float yaw, UUID owner, String name) {
        PostBlock pb = new PostBlock(UUID.randomUUID(), type, owner, name, target.getWorld().getName(),
                target.getX(), target.getY(), target.getZ(), yaw);
        store.blocks.put(pb.id, pb);
        index(pb);
        store.saveBlocks();
        refresh(pb);
        return pb;
    }

    static String trimName(String name) {
        name = name.replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.length() > 32) name = name.substring(0, 32);
        return name.isEmpty() ? "Mailbox" : name;
    }

    void tryBreak(Player p, PostBlock pb) {
        if (!pb.owner.equals(p.getUniqueId()) && !p.hasPermission("postoffice.admin")) {
            Msg.send(p, pb.type.isMailbox() ? "mailbox.not-yours" : "dropoff.not-yours",
                    "owner", nameOf(pb.owner), "mailbox", pb.name);
            return;
        }
        if (pb.type.isMailbox() && store.mailCount(pb.id) > 0) {
            Msg.send(p, "mailbox.collect-first", "count", String.valueOf(store.mailCount(pb.id)));
            return;
        }
        remove(pb);
        if (p.getGameMode() != org.bukkit.GameMode.CREATIVE) {
            ItemStack drop = MailItems.create(pb.type.kind);
            if (pb.type.isMailbox()) drop.editMeta(m -> m.customName(Msg.item("<white>" + escape(pb.name))));
            pb.bukkitWorld().dropItemNaturally(pb.center(), drop);
        }
        pb.bukkitWorld().playSound(pb.center(), Sound.BLOCK_METAL_BREAK, 1f, 1f);
        pb.bukkitWorld().spawnParticle(Particle.BLOCK, pb.center(), 20, 0.3, 0.3, 0.3, Material.IRON_BLOCK.createBlockData());
    }

    static String escape(String s) {
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(s);
    }

    void remove(PostBlock pb) {
        store.blocks.remove(pb.id);
        unindex(pb);
        store.saveBlocks();
        if (pb.type.isMailbox() && store.mailCount(pb.id) == 0) store.deleteMail(pb.id);
        Block b = pb.block();
        for (int i = 0; b != null && i < pb.height(); i++) {
            Block part = b.getRelative(0, i, 0);
            if (part.getType() == Material.BARRIER) part.setType(Material.AIR);
        }
        if (pb.isLoaded()) removeDisplays(pb, null);
    }

    void rename(PostBlock pb, String name) {
        pb.name = trimName(name);
        store.saveBlocks();
    }

    // ---------------- right-click ----------------

    void rightClick(Player p, PostBlock pb, EquipmentSlot hand) {
        if (!p.hasPermission("postoffice.use")) {
            Msg.send(p, "no-permission");
            return;
        }
        if (pb.type == PostBlock.Type.DROPOFF) {
            ItemStack held = p.getInventory().getItem(hand);
            MailKind kind = MailItems.kind(held);
            if (kind == null || !kind.sendable()) {
                Msg.send(p, "dropoff.hint");
                return;
            }
            if (registeredPlayers().isEmpty()) {
                Msg.send(p, "send.nobody");
                return;
            }
            int slot = hand == EquipmentSlot.OFF_HAND ? 40 : p.getInventory().getHeldItemSlot();
            new SendMenus.PickPlayer(plugin, new SendMenus.Context(p, slot, held.clone(), pb), 0).open(p);
            return;
        }
        if (!pb.owner.equals(p.getUniqueId())) {
            Msg.send(p, "mailbox.not-yours", "owner", nameOf(pb.owner), "mailbox", pb.name);
            return;
        }
        if (p.isSneaking()) {
            new MailboxMenu(plugin, pb).open(p);
        } else {
            collect(p, pb);
        }
    }

    /** Moves everything in the mailbox into the player's inventory; whatever doesn't fit stays. */
    void collect(Player p, PostBlock pb) {
        List<String> items = store.mail(pb.id);
        if (items.isEmpty()) {
            Msg.send(p, "mailbox.empty", "mailbox", pb.name);
            return;
        }
        List<String> kept = new ArrayList<>();
        int taken = 0;
        for (String data : items) {
            ItemStack item = ItemCodec.decode(data);
            if (item == null) continue;
            Map<Integer, ItemStack> left = p.getInventory().addItem(item);
            if (left.isEmpty()) taken++;
            else for (ItemStack l : left.values()) kept.add(ItemCodec.encode(l));
        }
        items.clear();
        items.addAll(kept);
        store.saveMail(pb.id);
        updateHologram(pb);
        if (taken > 0) {
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 0.8f);
            p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
            Msg.send(p, "mailbox.collected", "count", String.valueOf(taken), "mailbox", pb.name);
        }
        if (!kept.isEmpty()) Msg.send(p, "mailbox.inventory-full", "count", String.valueOf(kept.size()));
    }

    // ---------------- displays ----------------

    /** Removes and re-creates the model + hologram for a block (place, chunk load). */
    void refresh(PostBlock pb) {
        if (!pb.isLoaded()) return;
        removeDisplays(pb, null);
        // Older versions used barrier blocks; the shaped hitbox replaces them.
        for (int i = 0; i < pb.height(); i++) {
            Block part = pb.block().getRelative(0, i, 0);
            if (part.getType() == Material.BARRIER) part.setType(Material.AIR, false);
        }
        Displays.spawnModel(pb.center(), pb.type.kind.id, pb.yaw, Keys.BLOCK_ID, pb.id.toString(), "model");
        Hitboxes.spawn(pb.center(), pb.yaw, pb.type.kind.id, Keys.BLOCK_ID, pb.id.toString());
        updateHologram(pb);
    }

    private List<Entity> displays(PostBlock pb) {
        List<Entity> out = new ArrayList<>();
        String id = pb.id.toString();
        for (Entity e : pb.bukkitWorld().getNearbyEntities(pb.center(), 1.0, 2.5, 1.0)) {
            if (id.equals(Displays.tagOf(e, Keys.BLOCK_ID))) out.add(e);
        }
        return out;
    }

    private void removeDisplays(PostBlock pb, String role) {
        for (Entity e : displays(pb)) if (role == null || role.equals(Displays.roleOf(e))) e.remove();
    }

    void updateHologram(PostBlock pb) {
        if (!pb.type.isMailbox() || !pb.isLoaded()) return;
        int count = store.mailCount(pb.id);
        TextDisplay holo = null;
        for (Entity e : displays(pb)) {
            if ("holo".equals(Displays.roleOf(e)) && e instanceof TextDisplay t) {
                if (holo == null) holo = t;
                else e.remove();
            }
        }
        if (count == 0) {
            if (holo != null) holo.remove();
            return;
        }
        var text = Msg.get("mailbox.hologram", "count", String.valueOf(count), "mailbox", pb.name);
        if (holo == null) {
            Location at = pb.center().add(0, plugin.getConfig().getDouble("mailbox.hologram-height", 0.95), 0);
            pb.bukkitWorld().spawn(at, TextDisplay.class, t -> {
                t.text(text);
                t.setBillboard(Display.Billboard.CENTER);
                t.setShadowed(true);
                t.setPersistent(true);
                Displays.tag(t, Keys.BLOCK_ID, pb.id.toString(), "holo");
            });
        } else {
            holo.text(text);
        }
    }

    /** Front of the box (where the Postman stands). */
    Location front(PostBlock pb) {
        double rad = Math.toRadians(pb.yaw);
        Vector dir = new Vector(-Math.sin(rad), 0, Math.cos(rad));
        return pb.center().add(dir).add(0, -0.5, 0);
    }
}

package click.portl.postoffice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Boxes on the ground.
 * <ul>
 *   <li>Place an Empty Box: it sits open. Right-click holding items to put the stack in (up to 8
 *       stacks), right-click with an empty hand to take the last one back out, sneak + right-click
 *       to seal it.</li>
 *   <li>Place a sealed parcel: right-click opens it (flaps swing open, the contents float up and
 *       take turns showing); then it works like an open box.</li>
 *   <li>Breaking a sealed box drops the parcel with everything inside; breaking an open one drops the
 *       empty box and the items separately.</li>
 * </ul>
 */
final class Parcels {
    static final int MAX = 8;
    private static final float OPEN_ANGLE = (float) Math.toRadians(125);
    // The flap's hinge (its outer top edge) in display space: 16 model units = 1 block, origin at the
    // centre. Item displays draw the model turned 180 degrees, so the flap's outer edge is at +z.
    private static final Vector3f HINGE = new Vector3f(0f, 0.140625f, 0.375f);

    private final PostOfficePlugin plugin;
    private final DataStore store;
    private final Map<BlockKey, PlacedParcel> byLoc = new HashMap<>();
    private final Map<UUID, UUID> payloadDisplays = new HashMap<>();
    private final Map<UUID, Integer> showing = new HashMap<>(); // which item an open box is showing
    private float spin;
    private int ticks;

    Parcels(PostOfficePlugin plugin, DataStore store) {
        this.plugin = plugin;
        this.store = store;
        for (PlacedParcel p : store.parcels.values()) byLoc.put(p.key(), p);
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::spinTick, 20L, 10L);
    }

    PlacedParcel byId(UUID id) {
        return id == null ? null : store.parcels.get(id);
    }

    PlacedParcel at(Block b) {
        return b == null ? null : byLoc.get(BlockKey.of(b));
    }

    Iterable<PlacedParcel> all() {
        return store.parcels.values();
    }

    /** Puts a sealed parcel (closed) or an Empty Box (open, ready to pack) on the ground. */
    boolean place(Player p, Block clicked, BlockFace face, ItemStack hand, EquipmentSlot slot) {
        MailKind kind = MailItems.kind(hand);
        if (kind != MailKind.PARCEL && kind != MailKind.EMPTY_BOX) return false;
        Block target = PostBlocks.placeTarget(clicked, face);
        if (target == null || !PostBlocks.claimBlock(p, target, clicked, hand, slot)) return false;
        target.setType(Material.AIR, false); // the shaped hitbox does the colliding
        List<String> contents = kind == MailKind.PARCEL ? MailItems.contentsData(hand) : List.of();
        PlacedParcel pp = new PlacedParcel(UUID.randomUUID(), target.getWorld().getName(), target.getX(), target.getY(),
                target.getZ(), PostBlocks.snapYaw(p.getYaw()), contents, kind == MailKind.EMPTY_BOX);
        store.parcels.put(pp.id, pp);
        byLoc.put(pp.key(), pp);
        store.saveParcels();
        if (p.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
        refresh(pp);
        target.getWorld().playSound(pp.center(), Sound.BLOCK_WOOD_PLACE, 1f, 1.2f);
        if (kind == MailKind.EMPTY_BOX) Msg.send(p, "parcel.packing-hint");
        return true;
    }

    void rightClick(Player p, PlacedParcel pp) {
        if (!pp.open) {
            open(pp);
            return;
        }
        if (p.isSneaking()) {
            seal(p, pp);
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (ItemCodec.isEmpty(hand)) takeLast(p, pp);
        else putIn(p, pp, hand);
    }

    // ---------------- packing ----------------

    private void putIn(Player p, PlacedParcel pp, ItemStack hand) {
        if (pp.contents.size() >= MAX) {
            Msg.send(p, "parcel.full-message");
            return;
        }
        if (!MailItems.allowedInParcel(hand)) {
            Msg.send(p, "parcel.not-allowed");
            return;
        }
        ItemStack stack = hand.clone();
        pp.contents.add(ItemCodec.encode(stack));
        p.getInventory().setItemInMainHand(null);
        store.saveParcels();
        showing.put(pp.id, pp.contents.size() - 1);
        updateContents(pp);
        p.playSound(pp.center(), Sound.ITEM_BUNDLE_INSERT, 1f, 1f);
        Msg.send(p, "parcel.added", "count", String.valueOf(stack.getAmount()), "item", MailItems.displayName(stack),
                "used", String.valueOf(pp.contents.size()), "max", String.valueOf(MAX));
    }

    private void takeLast(Player p, PlacedParcel pp) {
        if (pp.contents.isEmpty()) {
            Msg.send(p, "parcel.empty");
            return;
        }
        ItemStack item = ItemCodec.decode(pp.contents.removeLast());
        store.saveParcels();
        showing.put(pp.id, Math.max(0, pp.contents.size() - 1));
        updateContents(pp);
        if (item != null) {
            Location c = pp.center();
            for (ItemStack left : p.getInventory().addItem(item).values()) c.getWorld().dropItemNaturally(c, left);
            p.playSound(c, Sound.ITEM_BUNDLE_REMOVE_ONE, 1f, 1f);
            Msg.send(p, "parcel.took", "count", String.valueOf(item.getAmount()), "item", MailItems.displayName(item));
        }
    }

    private void seal(Player p, PlacedParcel pp) {
        if (pp.contents.isEmpty()) {
            Msg.send(p, "parcel.empty-seal");
            return;
        }
        pp.open = false;
        store.saveParcels();
        removeDisplays(pp, "payload");
        removeDisplays(pp, "holo");
        // Flaps fold shut, then the closed box replaces the open one.
        for (Entity e : nearby(pp)) {
            if (e instanceof ItemDisplay d && ("flap_a".equals(Displays.roleOf(e)) || "flap_b".equals(Displays.roleOf(e)))) {
                Displays.animate(d, flapTransform("flap_b".equals(Displays.roleOf(e)), 0f), 10);
            }
        }
        Location c = pp.center();
        c.getWorld().playSound(c, Sound.BLOCK_BARREL_CLOSE, 1f, 1.3f);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (store.parcels.containsKey(pp.id) && !pp.open) refresh(pp);
        }, 12L);
        Msg.send(p, "parcel.sealed");
    }

    // ---------------- opening ----------------

    private void open(PlacedParcel pp) {
        pp.open = true;
        store.saveParcels();
        removeDisplays(pp, "box");
        Location c = pp.center();
        Displays.spawnModel(c, "parcel_open", pp.yaw, Keys.PARCEL_ID, pp.id.toString(), "box");
        ItemDisplay a = spawnFlap(pp, "flap_a", 0);
        ItemDisplay b = spawnFlap(pp, "flap_b", 0);
        showing.put(pp.id, 0);
        ItemDisplay item = spawnPayload(pp, false);
        c.getWorld().playSound(c, Sound.BLOCK_BARREL_OPEN, 1f, 1.3f);
        c.getWorld().playSound(c, Sound.ITEM_BUNDLE_DROP_CONTENTS, 0.8f, 1f);
        // Let the client see the closed pose first, then interpolate to open.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (a.isValid()) Displays.animate(a, flapTransform(false, OPEN_ANGLE), 12);
            if (b.isValid()) Displays.animate(b, flapTransform(true, OPEN_ANGLE), 12);
            if (item != null && item.isValid()) Displays.animate(item, payloadTransform(true, 0f), 25);
            c.getWorld().spawnParticle(Particle.BLOCK, c.clone().add(0, 0.2, 0), 12, 0.25, 0.1, 0.25,
                    Material.BROWN_WOOL.createBlockData());
            updateFullSign(pp);
        }, 3L);
    }

    // ---------------- breaking ----------------

    void breakParcel(PlacedParcel pp) {
        Location c = pp.center();
        List<ItemStack> items = new ArrayList<>();
        for (String data : pp.contents) {
            ItemStack it = ItemCodec.decode(data);
            if (it != null) items.add(it);
        }
        boolean sealed = !pp.open;
        remove(pp);
        if (sealed) {
            c.getWorld().dropItemNaturally(c, MailItems.parcel(items, null));
        } else {
            for (ItemStack it : items) c.getWorld().dropItemNaturally(c, it);
            c.getWorld().dropItemNaturally(c, MailItems.create(MailKind.EMPTY_BOX));
        }
        c.getWorld().playSound(c, Sound.BLOCK_WOOD_BREAK, 1f, 1.2f);
        c.getWorld().spawnParticle(Particle.BLOCK, c, 20, 0.3, 0.3, 0.3, Material.BROWN_WOOL.createBlockData());
    }

    private void remove(PlacedParcel pp) {
        store.parcels.remove(pp.id);
        byLoc.remove(pp.key());
        payloadDisplays.remove(pp.id);
        showing.remove(pp.id);
        store.saveParcels();
        Block b = pp.bukkitWorld().getBlockAt(pp.x, pp.y, pp.z);
        if (b.getType() == Material.BARRIER) b.setType(Material.AIR);
        removeDisplays(pp, null);
    }

    // ---------------- displays ----------------

    /** Rebuilds the displays without animation (on place and chunk load). */
    void refresh(PlacedParcel pp) {
        if (pp.bukkitWorld() == null || !pp.bukkitWorld().isChunkLoaded(pp.x >> 4, pp.z >> 4)) return;
        removeDisplays(pp, null);
        Block old = pp.bukkitWorld().getBlockAt(pp.x, pp.y, pp.z);
        if (old.getType() == Material.BARRIER) old.setType(Material.AIR, false);
        Location c = pp.center();
        Hitboxes.spawn(c, pp.yaw, "parcel", Keys.PARCEL_ID, pp.id.toString());
        if (!pp.open) {
            Displays.spawnModel(c, "parcel_closed", pp.yaw, Keys.PARCEL_ID, pp.id.toString(), "box");
            return;
        }
        Displays.spawnModel(c, "parcel_open", pp.yaw, Keys.PARCEL_ID, pp.id.toString(), "box");
        spawnFlap(pp, "flap_a", OPEN_ANGLE);
        spawnFlap(pp, "flap_b", OPEN_ANGLE);
        updateContents(pp);
    }

    /** The floating item and the "Box full" sign of an open box. */
    private void updateContents(PlacedParcel pp) {
        removeDisplays(pp, "payload");
        payloadDisplays.remove(pp.id);
        if (pp.open && !pp.contents.isEmpty()) spawnPayload(pp, true);
        updateFullSign(pp);
    }

    private void updateFullSign(PlacedParcel pp) {
        removeDisplays(pp, "holo");
        if (!pp.open || pp.contents.size() < MAX) return;
        Location at = pp.center().add(0, 0.95, 0);
        at.getWorld().spawn(at, TextDisplay.class, t -> {
            t.text(Msg.get("parcel.full-sign"));
            t.setBillboard(Display.Billboard.CENTER);
            t.setShadowed(true);
            t.setPersistent(true);
            Displays.tag(t, Keys.PARCEL_ID, pp.id.toString(), "holo");
        });
    }

    private ItemDisplay spawnFlap(PlacedParcel pp, String role, float angle) {
        ItemDisplay d = Displays.spawnModel(pp.center(), "parcel_flap", pp.yaw, Keys.PARCEL_ID, pp.id.toString(), role);
        d.setTransformation(flapTransform(role.equals("flap_b"), angle));
        return d;
    }

    private ItemStack shownItem(PlacedParcel pp) {
        if (pp.contents.isEmpty()) return null;
        int i = Math.floorMod(showing.getOrDefault(pp.id, 0), pp.contents.size());
        ItemStack it = ItemCodec.decode(pp.contents.get(i));
        if (it == null) return null;
        it = it.clone();
        it.setAmount(1);
        return it;
    }

    private ItemDisplay spawnPayload(PlacedParcel pp, boolean raised) {
        ItemStack shown = shownItem(pp);
        if (shown == null) return null;
        Location at = pp.center();
        at.setYaw(pp.yaw);
        ItemDisplay d = at.getWorld().spawn(at, ItemDisplay.class, e -> {
            e.setItemStack(shown);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            e.setPersistent(true);
            e.setTransformation(payloadTransform(raised, spin));
            Displays.tag(e, Keys.PARCEL_ID, pp.id.toString(), "payload");
        });
        payloadDisplays.put(pp.id, d.getUniqueId());
        return d;
    }

    /** Rotate the flap about its hinge: translation = base*h - q*h, rotation q = base * rotX(angle). */
    private static Transformation flapTransform(boolean otherSide, float angle) {
        Quaternionf base = otherSide ? new Quaternionf().rotationY((float) Math.PI) : new Quaternionf();
        Quaternionf q = new Quaternionf(base).rotateX(angle);
        Vector3f t = base.transform(new Vector3f(HINGE)).sub(q.transform(new Vector3f(HINGE)));
        return new Transformation(t, q, new Vector3f(1f, 1f, 1f), new Quaternionf());
    }

    private static Transformation payloadTransform(boolean raised, float spinAngle) {
        float y = raised ? 0.55f + 0.04f * (float) Math.sin(spinAngle * 2) : -0.15f;
        return Displays.transform(new Vector3f(0f, y, 0f), new Quaternionf().rotationY(spinAngle), raised ? 0.55f : 0.35f);
    }

    private List<Entity> nearby(PlacedParcel pp) {
        String id = pp.id.toString();
        List<Entity> out = new ArrayList<>();
        for (Entity e : pp.bukkitWorld().getNearbyEntities(pp.center(), 1.0, 1.5, 1.0)) {
            if (id.equals(Displays.tagOf(e, Keys.PARCEL_ID))) out.add(e);
        }
        return out;
    }

    private void removeDisplays(PlacedParcel pp, String role) {
        for (Entity e : nearby(pp)) if (role == null || role.equals(Displays.roleOf(e))) e.remove();
    }

    /** Turns the floating item of every open box, and every 2 seconds shows the next item in it. */
    private void spinTick() {
        spin += (float) (Math.PI / 4);
        ticks++;
        for (PlacedParcel pp : store.parcels.values()) {
            if (!pp.open || pp.contents.isEmpty()) continue;
            UUID did = payloadDisplays.get(pp.id);
            if (did == null) continue;
            Entity e = Bukkit.getEntity(did);
            if (!(e instanceof ItemDisplay d) || !d.isValid()) {
                payloadDisplays.remove(pp.id);
                continue;
            }
            if (ticks % 4 == 0 && pp.contents.size() > 1) {
                showing.merge(pp.id, 1, Integer::sum);
                ItemStack next = shownItem(pp);
                if (next != null) d.setItemStack(next);
            }
            Displays.animate(d, payloadTransform(true, spin), 10);
        }
    }
}

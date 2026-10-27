package click.portl.postoffice;

import java.util.HashMap;
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
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Parcels placed on the ground. First right-click: the flaps swing open and the contents float up.
 * Second right-click: take the contents and get the empty box back. Breaking it drops both.
 */
final class Parcels {
    private static final float OPEN_ANGLE = (float) Math.toRadians(-125);
    // The flap model's hinge (outer top edge) in display space: 16 model units = 1 block, origin at the centre.
    private static final Vector3f HINGE = new Vector3f(0f, 0.140625f, -0.375f);

    private final PostOfficePlugin plugin;
    private final DataStore store;
    private final Map<BlockKey, PlacedParcel> byLoc = new HashMap<>();
    private final Map<UUID, UUID> payloadDisplays = new HashMap<>();
    private float spin;

    Parcels(PostOfficePlugin plugin, DataStore store) {
        this.plugin = plugin;
        this.store = store;
        for (PlacedParcel p : store.parcels.values()) byLoc.put(p.key(), p);
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::spinTick, 20L, 10L);
    }

    PlacedParcel at(Block b) {
        return b == null ? null : byLoc.get(BlockKey.of(b));
    }

    Iterable<PlacedParcel> all() {
        return store.parcels.values();
    }

    boolean place(Player p, Block clicked, BlockFace face, ItemStack hand, EquipmentSlot slot) {
        String payload = MailItems.payloadData(hand);
        if (payload == null) return false;
        Block target = PostBlocks.placeTarget(clicked, face);
        if (target == null || !PostBlocks.claimBlock(p, target, clicked, hand, slot)) return false;
        PlacedParcel pp = new PlacedParcel(UUID.randomUUID(), target.getWorld().getName(), target.getX(), target.getY(),
                target.getZ(), PostBlocks.snapYaw(p.getYaw()), payload, false);
        store.parcels.put(pp.id, pp);
        byLoc.put(pp.key(), pp);
        store.saveParcels();
        if (p.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
        refresh(pp);
        target.getWorld().playSound(pp.center(), Sound.BLOCK_WOOD_PLACE, 1f, 1.2f);
        return true;
    }

    void rightClick(Player p, PlacedParcel pp) {
        if (!pp.open) open(pp);
        else claim(p, pp);
    }

    private void open(PlacedParcel pp) {
        pp.open = true;
        store.saveParcels();
        removeDisplays(pp, "box");
        Location c = pp.center();
        Displays.spawnModel(c, "parcel_open", pp.yaw, Keys.PARCEL_ID, pp.id.toString(), "box");
        ItemDisplay a = spawnFlap(pp, "flap_a", 0);
        ItemDisplay b = spawnFlap(pp, "flap_b", 0);
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
        }, 3L);
    }

    private void claim(Player p, PlacedParcel pp) {
        ItemStack payload = ItemCodec.decode(pp.payload);
        Location c = pp.center();
        remove(pp);
        if (payload != null) {
            for (ItemStack left : p.getInventory().addItem(payload).values()) c.getWorld().dropItemNaturally(c, left);
        }
        c.getWorld().dropItemNaturally(c, MailItems.create(MailKind.EMPTY_BOX));
        c.getWorld().playSound(c, Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
        if (payload != null) Msg.send(p, "parcel.claimed", "item", MailItems.displayName(payload), "count", String.valueOf(payload.getAmount()));
    }

    void breakParcel(PlacedParcel pp) {
        ItemStack payload = ItemCodec.decode(pp.payload);
        Location c = pp.center();
        remove(pp);
        if (payload != null) c.getWorld().dropItemNaturally(c, payload);
        c.getWorld().dropItemNaturally(c, MailItems.create(MailKind.EMPTY_BOX));
        c.getWorld().playSound(c, Sound.BLOCK_WOOD_BREAK, 1f, 1.2f);
        c.getWorld().spawnParticle(Particle.BLOCK, c, 20, 0.3, 0.3, 0.3, Material.BROWN_WOOL.createBlockData());
    }

    private void remove(PlacedParcel pp) {
        store.parcels.remove(pp.id);
        byLoc.remove(pp.key());
        payloadDisplays.remove(pp.id);
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
        Location c = pp.center();
        if (!pp.open) {
            Displays.spawnModel(c, "parcel_closed", pp.yaw, Keys.PARCEL_ID, pp.id.toString(), "box");
            return;
        }
        Displays.spawnModel(c, "parcel_open", pp.yaw, Keys.PARCEL_ID, pp.id.toString(), "box");
        spawnFlap(pp, "flap_a", OPEN_ANGLE);
        spawnFlap(pp, "flap_b", OPEN_ANGLE);
        spawnPayload(pp, true);
    }

    private ItemDisplay spawnFlap(PlacedParcel pp, String role, float angle) {
        ItemDisplay d = Displays.spawnModel(pp.center(), "parcel_flap", pp.yaw, Keys.PARCEL_ID, pp.id.toString(), role);
        d.setTransformation(flapTransform(role.equals("flap_b"), angle));
        return d;
    }

    private ItemDisplay spawnPayload(PlacedParcel pp, boolean raised) {
        ItemStack payload = ItemCodec.decode(pp.payload);
        if (payload == null) return null;
        ItemStack shown = payload.clone();
        shown.setAmount(1);
        Location at = pp.center();
        at.setYaw(pp.yaw);
        ItemDisplay d = at.getWorld().spawn(at, ItemDisplay.class, e -> {
            e.setItemStack(shown);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            e.setPersistent(true);
            e.setTransformation(payloadTransform(raised, 0f));
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

    private void removeDisplays(PlacedParcel pp, String role) {
        String id = pp.id.toString();
        for (Entity e : pp.bukkitWorld().getNearbyEntities(pp.center(), 1.0, 1.5, 1.0)) {
            if (id.equals(Displays.tagOf(e, Keys.PARCEL_ID)) && (role == null || role.equals(Displays.roleOf(e)))) e.remove();
        }
    }

    /** Slowly turns the floating contents of every open parcel. */
    private void spinTick() {
        spin += (float) (Math.PI / 4);
        for (PlacedParcel pp : store.parcels.values()) {
            if (!pp.open) continue;
            UUID did = payloadDisplays.get(pp.id);
            if (did == null) continue;
            Entity e = Bukkit.getEntity(did);
            if (!(e instanceof ItemDisplay d) || !d.isValid()) {
                payloadDisplays.remove(pp.id);
                continue;
            }
            Displays.animate(d, payloadTransform(true, spin), 10);
        }
    }
}

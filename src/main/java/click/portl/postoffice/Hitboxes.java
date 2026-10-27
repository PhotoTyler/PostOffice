package click.portl.postoffice;

import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Shulker;

/**
 * Solid hitboxes shaped like the models. Barriers can only be full blocks, so instead each model
 * gets a few invisible, shrunk shulkers (players collide with shulkers like blocks). Shulkers snap to
 * the block grid unless they ride something, so each one rides an empty item display placed exactly.
 */
final class Hitboxes {
    /** A cube: centre x/z and bottom y in blocks relative to the block's bottom centre; front = +z. */
    record Box(double x, double y, double z, double size) {}

    private static final Map<String, List<Box>> SHAPES = Map.of(
            "dropoff", List.of(new Box(0, 0, 0, 0.625), new Box(0, 0.625, 0, 0.625), new Box(0, 1.25, 0, 0.625)),
            "mailbox_uk", List.of(
                    new Box(-0.156, 0.1875, -0.28, 0.4375), new Box(0.156, 0.1875, -0.28, 0.4375),
                    new Box(-0.156, 0.5, -0.28, 0.4375), new Box(0.156, 0.5, -0.28, 0.4375)),
            "mailbox_us", List.of(
                    new Box(0, 0, 0, 0.25), new Box(0, 0.25, 0, 0.25),
                    new Box(0, 0.4375, -0.125, 0.5), new Box(0, 0.4375, 0.125, 0.5)),
            "parcel", List.of(new Box(0, 0, 0, 0.6875)));

    private Hitboxes() {}

    /** Spawns the hitbox for {@code shape} at a block, its front facing {@code yaw}. */
    static void spawn(Location blockCenter, float yaw, String shape, NamespacedKey tag, String id) {
        List<Box> boxes = SHAPES.get(shape);
        if (boxes == null) return;
        double rad = Math.toRadians(yaw);
        double cos = Math.cos(rad), sin = Math.sin(rad);
        Location bottom = blockCenter.clone().add(0, -0.5, 0);
        for (Box b : boxes) {
            // Local +z is the front: rotate so it points along the yaw's facing direction.
            double wx = b.x() * cos - b.z() * sin;
            double wz = b.x() * sin + b.z() * cos;
            Location at = bottom.clone().add(wx, b.y(), wz);
            at.setYaw(0);
            at.setPitch(0);
            ItemDisplay anchor = at.getWorld().spawn(at, ItemDisplay.class, a -> {
                a.setPersistent(true);
                Displays.tag(a, tag, id, "anchor");
            });
            Shulker shulker = at.getWorld().spawn(at, Shulker.class, s -> {
                s.setAI(false);
                s.setInvisible(true);
                s.setSilent(true);
                s.setInvulnerable(true);
                s.setGravity(false);
                s.setPersistent(true);
                s.setRemoveWhenFarAway(false);
                s.setPeek(0f);
                AttributeInstance scale = s.getAttribute(Attribute.SCALE);
                if (scale != null) scale.setBaseValue(b.size());
                Displays.tag(s, tag, id, "hitbox");
            });
            anchor.addPassenger(shulker);
        }
    }

    static boolean isHitbox(Entity e) {
        return "hitbox".equals(Displays.roleOf(e));
    }
}

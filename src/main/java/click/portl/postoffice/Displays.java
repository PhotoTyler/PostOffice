package click.portl.postoffice;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Helpers for the ItemDisplay "blocks" drawn by the resource pack. */
final class Displays {
    private Displays() {}

    /** Entity yaw that makes an item display's model front face {@code yaw}. */
    static float facing(float yaw) {
        return yaw + 180f;
    }

    static ItemStack modelItem(String model) {
        ItemStack item = new ItemStack(Material.PAPER);
        item.editMeta(m -> m.setItemModel(Keys.model(model)));
        return item;
    }

    /** An ItemDisplay at the block centre showing a pack model 1:1 (16 model units = 1 block). */
    static ItemDisplay spawnModel(Location center, String model, float yaw, NamespacedKey tag, String id, String role) {
        Location at = center.clone();
        // Item displays draw a model turned 180 degrees from the entity's facing, so turn it back:
        // the model's front (south side) then faces {@code yaw}.
        at.setYaw(yaw + 180f);
        at.setPitch(0);
        return center.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(modelItem(model));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setPersistent(true);
            tag(d, tag, id, role);
        });
    }

    static void tag(Entity e, NamespacedKey tag, String id, String role) {
        e.getPersistentDataContainer().set(tag, PersistentDataType.STRING, id);
        e.getPersistentDataContainer().set(Keys.ROLE, PersistentDataType.STRING, role);
    }

    static String tagOf(Entity e, NamespacedKey tag) {
        return e.getPersistentDataContainer().get(tag, PersistentDataType.STRING);
    }

    static String roleOf(Entity e) {
        return e.getPersistentDataContainer().get(Keys.ROLE, PersistentDataType.STRING);
    }

    static Transformation transform(Vector3f translation, Quaternionf rotation, float scale) {
        return new Transformation(translation, rotation, new Vector3f(scale, scale, scale), new Quaternionf());
    }

    static void animate(Display d, Transformation t, int ticks) {
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(t);
    }
}

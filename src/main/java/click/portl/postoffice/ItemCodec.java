package click.portl.postoffice;

import java.util.Base64;
import org.bukkit.inventory.ItemStack;

/** ItemStack <-> Base64 using Paper's versioned byte format (survives Minecraft updates). */
final class ItemCodec {
    private ItemCodec() {}

    static String encode(ItemStack item) {
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    static ItemStack decode(String data) {
        if (data == null || data.isEmpty()) return null;
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(data));
        } catch (RuntimeException e) {
            return null;
        }
    }

    static boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }
}

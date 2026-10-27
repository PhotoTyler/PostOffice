package click.portl.postoffice;

import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import com.destroystokyo.paper.event.inventory.PrepareResultEvent;

/**
 * Crafting: letters, empty boxes, parcels (whole stack in the centre slot), mailboxes and drop-offs.
 * Also stops PostOffice items being used as plain paper in vanilla recipes.
 */
final class Recipes implements Listener {
    private final PostOfficePlugin plugin;
    private List<NamespacedKey> keys = List.of();

    Recipes(PostOfficePlugin plugin) {
        this.plugin = plugin;
    }

    void register() {
        RecipeChoice planks = new RecipeChoice.MaterialChoice(Tag.PLANKS);
        RecipeChoice anything = new RecipeChoice.MaterialChoice(MailItems.allItems());

        ShapelessRecipe letter = new ShapelessRecipe(Keys.RECIPE_LETTER, MailItems.blankLetter());
        letter.addIngredient(Material.PAPER);
        letter.addIngredient(Material.INK_SAC);

        ShapedRecipe box = new ShapedRecipe(Keys.RECIPE_EMPTY_BOX, MailItems.create(MailKind.EMPTY_BOX));
        box.shape("PSP", "W W", "PSP");
        box.setIngredient('P', planks);
        box.setIngredient('S', Material.STICK);
        box.setIngredient('W', Material.WHEAT);

        // Real result is filled in by onPrepare (it depends on the centre item).
        ShapedRecipe parcelChest = new ShapedRecipe(Keys.RECIPE_PARCEL_CHEST, MailItems.create(MailKind.PARCEL));
        parcelChest.shape("PSP", "WXW", "PSP");
        parcelChest.setIngredient('P', planks);
        parcelChest.setIngredient('S', Material.STICK);
        parcelChest.setIngredient('W', Material.WHEAT);
        parcelChest.setIngredient('X', anything);

        ShapelessRecipe parcelBox = new ShapelessRecipe(Keys.RECIPE_PARCEL_BOX, MailItems.create(MailKind.PARCEL));
        parcelBox.addIngredient(RecipeChoice.exactChoice(MailItems.create(MailKind.EMPTY_BOX)));
        parcelBox.addIngredient(anything);

        ShapedRecipe uk = new ShapedRecipe(Keys.RECIPE_MAILBOX_UK, MailItems.create(MailKind.MAILBOX_UK));
        uk.shape("IRI", "ICI", "III");
        uk.setIngredient('I', Material.IRON_INGOT);
        uk.setIngredient('R', Material.RED_DYE);
        uk.setIngredient('C', Material.CHEST);

        ShapedRecipe us = new ShapedRecipe(Keys.RECIPE_MAILBOX_US, MailItems.create(MailKind.MAILBOX_US));
        us.shape("IBI", "ICI", "I I");
        us.setIngredient('I', Material.IRON_INGOT);
        us.setIngredient('B', Material.BLUE_DYE);
        us.setIngredient('C', Material.CHEST);

        ShapedRecipe dropoff = new ShapedRecipe(Keys.RECIPE_DROPOFF, MailItems.create(MailKind.DROPOFF));
        dropoff.shape("RRR", "RCR", "III");
        dropoff.setIngredient('R', Material.RED_DYE);
        dropoff.setIngredient('I', Material.IRON_INGOT);
        dropoff.setIngredient('C', Material.CHEST);

        List<Recipe> all = List.of(letter, box, parcelChest, parcelBox, uk, us, dropoff);
        keys = all.stream().map(r -> ((Keyed) r).getKey()).toList();
        for (Recipe r : all) {
            Bukkit.removeRecipe(((Keyed) r).getKey(), false);
            Bukkit.addRecipe(r, false);
        }
        Bukkit.updateRecipes();
    }

    void unregister() {
        for (NamespacedKey k : keys) Bukkit.removeRecipe(k, false);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        e.getPlayer().discoverRecipes(keys);
    }

    private static NamespacedKey keyOf(Recipe r) {
        return r instanceof Keyed k ? k.getKey() : null;
    }

    private static boolean isParcelRecipe(NamespacedKey key) {
        return Keys.RECIPE_PARCEL_CHEST.equals(key) || Keys.RECIPE_PARCEL_BOX.equals(key);
    }

    /** Index of the item that goes into the parcel, or -1. */
    private static int payloadIndex(ItemStack[] matrix, NamespacedKey key) {
        if (Keys.RECIPE_PARCEL_CHEST.equals(key)) return matrix.length == 9 ? 4 : -1;
        // Shapeless: one empty box (skipped once) + one other item
        int found = -1;
        boolean boxSeen = false;
        for (int i = 0; i < matrix.length; i++) {
            ItemStack it = matrix[i];
            if (ItemCodec.isEmpty(it)) continue;
            if (!boxSeen && MailItems.kind(it) == MailKind.EMPTY_BOX) {
                boxSeen = true;
                continue;
            }
            if (found != -1) return -1;
            found = i;
        }
        return boxSeen ? found : -1;
    }

    private ItemStack parcelResult(ItemStack[] matrix, NamespacedKey key) {
        int idx = payloadIndex(matrix, key);
        if (idx < 0) return null;
        ItemStack payload = matrix[idx];
        if (ItemCodec.isEmpty(payload) || !MailItems.allowedInParcel(payload)) return null;
        return MailItems.parcel(payload.clone(), null);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepare(PrepareItemCraftEvent e) {
        CraftingInventory inv = e.getInventory();
        NamespacedKey key = e.getRecipe() == null ? null : keyOf(e.getRecipe());
        if (isParcelRecipe(key)) {
            inv.setResult(parcelResult(inv.getMatrix(), key));
            return;
        }
        if (key != null && key.getNamespace().equals(Keys.NS)) return;
        for (ItemStack it : inv.getMatrix()) {
            if (MailItems.kind(it) != null) {
                inv.setResult(null);
                return;
            }
        }
    }

    /** Anvils, smithing, grindstones, cartography tables... none of them get PostOffice items. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareResult(PrepareResultEvent e) {
        for (ItemStack it : e.getInventory().getContents()) {
            MailKind kind = MailItems.kind(it);
            // Renaming a mailbox in an anvil is allowed: the name becomes the mailbox name.
            if (kind != null && !(kind.isBlock() && e.getInventory().getType() == org.bukkit.event.inventory.InventoryType.ANVIL)) {
                e.setResult(null);
                return;
            }
        }
    }

    /** Parcels take the whole centre stack, so crafting is done by hand here. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        NamespacedKey key = keyOf(e.getRecipe());
        if (!isParcelRecipe(key)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player player)) return;
        CraftingInventory inv = e.getInventory();
        ItemStack[] matrix = inv.getMatrix();
        int idx = payloadIndex(matrix, key);
        ItemStack result = parcelResult(matrix, key);
        if (result == null || idx < 0) return;

        ClickType click = e.getClick();
        if (click.isShiftClick()) {
            if (player.getInventory().firstEmpty() == -1) return;
            player.getInventory().addItem(result);
        } else if (click == ClickType.LEFT || click == ClickType.RIGHT) {
            if (!ItemCodec.isEmpty(e.getView().getCursor())) return;
            e.getView().setCursor(result);
        } else {
            return;
        }

        for (int i = 0; i < matrix.length; i++) {
            if (ItemCodec.isEmpty(matrix[i])) continue;
            if (i == idx) {
                matrix[i] = null;
            } else {
                int left = matrix[i].getAmount() - 1;
                if (left <= 0) matrix[i] = null;
                else matrix[i].setAmount(left);
            }
        }
        inv.setMatrix(matrix);
        inv.setResult(null);
        Bukkit.getScheduler().runTask(plugin, player::updateInventory);
    }
}

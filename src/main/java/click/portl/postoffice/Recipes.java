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

        ShapelessRecipe letter = new ShapelessRecipe(Keys.RECIPE_LETTER, MailItems.blankLetter());
        letter.addIngredient(Material.PAPER);
        letter.addIngredient(Material.INK_SAC);

        ShapedRecipe box = new ShapedRecipe(Keys.RECIPE_EMPTY_BOX, MailItems.create(MailKind.EMPTY_BOX));
        box.shape("PSP", "W W", "PSP");
        box.setIngredient('P', planks);
        box.setIngredient('S', Material.STICK);
        box.setIngredient('W', Material.WHEAT);

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

        List<Recipe> all = List.of(letter, box, uk, us, dropoff);
        keys = all.stream().map(r -> ((Keyed) r).getKey()).toList();
        // Packing parcels isn't a registered recipe (see onPrepare); drop the old ones from 1.x.
        Bukkit.removeRecipe(Keys.RECIPE_PARCEL_CHEST, false);
        Bukkit.removeRecipe(Keys.RECIPE_PARCEL_BOX, false);
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

    /**
     * Packing: an Empty Box in the middle of the crafting table with 1-8 item stacks around it.
     * Returns the parcel, or null if the grid isn't that pattern.
     */
    private static ItemStack packedParcel(ItemStack[] matrix) {
        if (matrix.length != 9 || MailItems.kind(matrix[4]) != MailKind.EMPTY_BOX) return null;
        List<ItemStack> contents = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) {
            if (i == 4 || ItemCodec.isEmpty(matrix[i])) continue;
            if (!MailItems.allowedInParcel(matrix[i])) return null;
            contents.add(matrix[i].clone());
        }
        return contents.isEmpty() ? null : MailItems.parcel(contents, null);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepare(PrepareItemCraftEvent e) {
        CraftingInventory inv = e.getInventory();
        ItemStack packed = packedParcel(inv.getMatrix());
        if (packed != null) {
            inv.setResult(packed);
            return;
        }
        NamespacedKey key = e.getRecipe() == null ? null : keyOf(e.getRecipe());
        if (key != null && key.getNamespace().equals(Keys.NS)) return;
        for (ItemStack it : inv.getMatrix()) {
            if (MailItems.kind(it) != null) {
                inv.setResult(null);
                return;
            }
        }
    }

    /** Taking a packed parcel uses up whole stacks (and one box), so it's done by hand here. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTakePacked(org.bukkit.event.inventory.InventoryClickEvent e) {
        if (!(e.getInventory() instanceof CraftingInventory inv) || e.getSlotType() != org.bukkit.event.inventory.InventoryType.SlotType.RESULT) return;
        ItemStack[] matrix = inv.getMatrix();
        ItemStack result = packedParcel(matrix);
        if (result == null) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player player)) return;
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
        for (int i = 0; i < 9; i++) {
            if (i == 4) {
                int left = matrix[4].getAmount() - 1;
                if (left <= 0) matrix[4] = null;
                else matrix[4].setAmount(left);
            } else {
                matrix[i] = null;
            }
        }
        inv.setMatrix(matrix);
        inv.setResult(packedParcel(matrix));
        player.playSound(player.getLocation(), org.bukkit.Sound.ITEM_BUNDLE_INSERT, 1f, 1f);
        Bukkit.getScheduler().runTask(plugin, player::updateInventory);
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
}

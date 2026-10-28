package click.portl.mysterymail;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.GameMode;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Ashes: what burned letters and boxes turn into. Gunpowder underneath (with its own texture from
 * the PostOffice pack), but never usable as gunpowder: no TNT, fireworks, fire charges or potions.
 * <ul>
 *   <li>Right-click a crop: a chance to grow it one stage (much weaker than bone meal).</li>
 *   <li>Right-click a composter: a small chance to raise its level, so ashes slowly become bone meal.</li>
 * </ul>
 */
final class Ashes implements Listener {
    static final NamespacedKey KEY = new NamespacedKey("mysterymail", "ashes");
    static final float CMD = 7101f;

    private final MysteryMail plugin;

    Ashes(MysteryMail plugin) {
        this.plugin = plugin;
    }

    static ItemStack item(int amount) {
        ItemStack ashes = new ItemStack(Material.GUNPOWDER, amount);
        ashes.editMeta(m -> {
            m.setItemModel(new NamespacedKey("postoffice", "ashes"));
            var cmd = m.getCustomModelDataComponent();
            cmd.setFloats(List.of(CMD));
            m.setCustomModelDataComponent(cmd);
            m.itemName(Component.text("Ashes"));
            m.lore(List.of(
                    Component.text("What's left of some old mail", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("Sprinkle on crops or compost it", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
            m.getPersistentDataContainer().set(KEY, PersistentDataType.BOOLEAN, true);
        });
        return ashes;
    }

    static boolean isAshes(ItemStack item) {
        return item != null && !item.isEmpty() && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(KEY);
    }

    /** Ashes, or a "Letters/Boxes to Burn" stack: MysteryMail's own items. */
    static boolean isOurs(ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) return false;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(KEY) || pdc.has(Burning.FUEL);
    }

    // ---------------- crops and composters ----------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        if (e.useItemInHand() == org.bukkit.event.Event.Result.DENY || !isAshes(e.getItem())) return;
        Player p = e.getPlayer();
        Block b = e.getClickedBlock();
        EquipmentSlot hand = e.getHand() == null ? EquipmentSlot.HAND : e.getHand();
        if (b.getType() == Material.COMPOSTER) {
            if (!(b.getBlockData() instanceof Levelled l) || l.getLevel() >= 7) return; // full: let the game hand out bone meal
            e.setCancelled(true);
            use(p, hand);
            boolean rises = roll("ashes.composter-chance", 0.1);
            Location c = b.getLocation().add(0.5, 0.6, 0.5);
            b.getWorld().playSound(c, rises ? Sound.BLOCK_COMPOSTER_FILL_SUCCESS : Sound.BLOCK_COMPOSTER_FILL, 1f, 1f);
            b.getWorld().spawnParticle(Particle.COMPOSTER, c, 8, 0.25, 0.1, 0.25, 0);
            if (!rises) return;
            l.setLevel(l.getLevel() + 1);
            b.setBlockData(l);
            if (l.getLevel() == 7) {
                // The game only turns 7 into "ready" from its own compost code: do the same a second later.
                org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (b.getBlockData() instanceof Levelled now && b.getType() == Material.COMPOSTER && now.getLevel() == 7) {
                        now.setLevel(8);
                        b.setBlockData(now);
                        b.getWorld().playSound(c, Sound.BLOCK_COMPOSTER_READY, 1f, 1f);
                    }
                }, 20L);
            }
            return;
        }
        if (!isCrop(b) || !(b.getBlockData() instanceof Ageable crop) || crop.getAge() >= crop.getMaximumAge()) return;
        e.setCancelled(true);
        use(p, hand);
        Location c = b.getLocation().add(0.5, 0.4, 0.5);
        if (roll("ashes.crop-chance", 0.25)) {
            crop.setAge(crop.getAge() + 1);
            b.setBlockData(crop);
            b.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, c, 6, 0.3, 0.2, 0.3, 0);
            b.getWorld().playSound(c, Sound.ITEM_BONE_MEAL_USE, 0.8f, 1.2f);
        } else {
            b.getWorld().spawnParticle(Particle.WHITE_ASH, c, 12, 0.3, 0.2, 0.3, 0);
            b.getWorld().playSound(c, Sound.BLOCK_SAND_STEP, 0.6f, 1.4f);
        }
    }

    /** Crops ashes can help: farm crops, stems, sweet berries and cocoa (not tall pitcher plants). */
    private static boolean isCrop(Block b) {
        Material m = b.getType();
        if (m == Material.PITCHER_CROP) return false;
        return Tag.CROPS.isTagged(m) || m == Material.SWEET_BERRY_BUSH || m == Material.COCOA;
    }

    private boolean roll(String path, double def) {
        return ThreadLocalRandom.current().nextDouble() < plugin.getConfig().getDouble(path, def);
    }

    private static void use(Player p, EquipmentSlot hand) {
        p.swingHand(hand);
        if (p.getGameMode() == GameMode.CREATIVE) return;
        ItemStack held = p.getInventory().getItem(hand);
        if (held.getAmount() > 1) held.setAmount(held.getAmount() - 1);
        else p.getInventory().setItem(hand, null);
    }

    // ---------------- never as gunpowder ----------------

    private static boolean ownRecipe(Object recipe) {
        return recipe instanceof Keyed k && k.getKey().getNamespace().equals("mysterymail");
    }

    /** TNT, fireworks, fire charges...: no recipe but MysteryMail's own may use its items. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onCraft(PrepareItemCraftEvent e) {
        if (ownRecipe(e.getRecipe())) return;
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (isOurs(it)) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    /** The crafter block skips the prepare event. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCrafter(CrafterCraftEvent e) {
        if (ownRecipe(e.getRecipe())) return;
        if (e.getBlock().getState() instanceof org.bukkit.block.Crafter crafter) {
            for (ItemStack it : crafter.getInventory().getContents()) {
                if (isOurs(it)) {
                    e.setCancelled(true);
                    return;
                }
            }
        }
    }

    /** Grindstone, cartography table, loom, stonecutter, smithing table, anvil. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareResult(com.destroystokyo.paper.event.inventory.PrepareResultEvent e) {
        for (ItemStack it : e.getInventory().getContents()) {
            if (isOurs(it)) {
                e.setResult(null);
                return;
            }
        }
    }

    /** Brewing stands (gunpowder makes splash potions) and real villagers' trades. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (e.getView().getTopInventory() instanceof BrewerInventory brewer) {
            boolean intoStand = e.getRawSlot() < brewer.getSize() && isOurs(e.getCursor());
            boolean shifted = e.isShiftClick() && e.getRawSlot() >= brewer.getSize() && isOurs(e.getCurrentItem());
            boolean hotkey = e.getRawSlot() < brewer.getSize() && e.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY
                    && isOurs(e.getWhoClicked().getInventory().getItem(e.getHotbarButton()));
            if (intoStand || shifted || hotkey) e.setCancelled(true);
            return;
        }
        if (e.getInventory() instanceof MerchantInventory inv && e.getSlotType() == InventoryType.SlotType.RESULT
                && inv.getMerchant() instanceof Entity && (isOurs(inv.getItem(0)) || isOurs(inv.getItem(1)))) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory() instanceof BrewerInventory brewer) || !isOurs(e.getOldCursor())) return;
        for (int raw : e.getRawSlots()) {
            if (raw < brewer.getSize()) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent e) {
        if (e.getDestination() instanceof BrewerInventory && isOurs(e.getItem())) e.setCancelled(true);
    }
}

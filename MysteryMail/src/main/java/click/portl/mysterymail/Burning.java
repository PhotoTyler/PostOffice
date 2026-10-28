package click.portl.mysterymail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.Furnace;
import org.bukkit.block.data.Lightable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.SmokingRecipe;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;

/**
 * Getting rid of old mail:
 * <ul>
 *   <li>Right-click a lit campfire, a torch or lava with a letter or box: it sizzles away into Ashes.
 *       Anything inside a parcel (or clipped to a letter) pops out first, so nothing is lost.</li>
 *   <li>Letters and boxes burn as furnace fuel (furnace, smoker, blast furnace). The game doesn't
 *       count paper as fuel, so the fuel slot is filled and burned by this class.</li>
 *   <li>In a furnace's top slot they cook into Ashes.</li>
 * </ul>
 */
final class Burning implements Listener {
    private static final NamespacedKey TYPE = new NamespacedKey("postoffice", "type");
    private static final NamespacedKey PAYLOADS = new NamespacedKey("postoffice", "payloads");
    private static final NamespacedKey PAYLOAD = new NamespacedKey("postoffice", "payload");
    private static final NamespacedKey ATTACHMENT = new NamespacedKey("postoffice", "attachment");
    static final NamespacedKey FUEL = new NamespacedKey("mysterymail", "fuel");
    static final NamespacedKey OLD_CHARCOAL_RECIPE = new NamespacedKey("mysterymail", "letters_to_charcoal");
    static final NamespacedKey LETTERS_RECIPE = new NamespacedKey("mysterymail", "letters_to_ashes");
    static final NamespacedKey BOXES_RECIPE = new NamespacedKey("mysterymail", "boxes_to_ashes");
    /** Custom model data for Bedrock (Geyser) players: Java uses the item model. */
    static final float CMD_LETTERS = 7102f, CMD_BOXES = 7103f;
    private static final Set<String> BURNABLE = Set.of("letter", "sealed_letter", "parcel", "empty_box");

    private final MysteryMail plugin;
    private final PostOfficeBridge post;
    private final Map<UUID, Integer> lastBurn = new HashMap<>();
    private final Set<Location> furnaces = new HashSet<>(); // furnaces with mail in the fuel slot
    private final Map<String, Optional<CookingRecipe<?>>> recipes = new HashMap<>();

    Burning(MysteryMail plugin, PostOfficeBridge post) {
        this.plugin = plugin;
        this.post = post;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickFurnaces, 20L, 5L);
    }

    /** "letter", "parcel", ... for PostOffice mail that can burn, else null. */
    private static String kind(ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) return null;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        String fuel = pdc.get(FUEL, PersistentDataType.STRING);
        if (fuel != null) return fuel.equals("letter") ? "letter_fuel" : "box_fuel";
        String k = pdc.get(TYPE, PersistentDataType.STRING);
        return k != null && BURNABLE.contains(k) ? k : null;
    }

    /**
     * In a furnace every letter becomes the same plain "letters to burn" item (and every box the
     * same "boxes to burn"), so different letters stack there. Outside a furnace they never stack.
     * They're gunpowder underneath, not paper, so no paper recipe (books, maps, Sticky Bags'
     * hardening) can ever pick them up; only MysteryMail's own furnace recipes match them exactly.
     */
    static ItemStack fuelForm(ItemStack mail, int amount) {
        boolean letter = kind(mail).contains("letter");
        ItemStack fuel = new ItemStack(Material.GUNPOWDER, amount);
        fuel.editMeta(m -> {
            var cmd = m.getCustomModelDataComponent();
            cmd.setFloats(List.of(letter ? CMD_LETTERS : CMD_BOXES));
            m.setCustomModelDataComponent(cmd);
            m.setItemModel(new NamespacedKey("postoffice", letter ? "sealed_letter" : "empty_box"));
            m.itemName(net.kyori.adventure.text.Component.text(letter ? "Letters to Burn" : "Boxes to Burn"));
            m.lore(List.of(net.kyori.adventure.text.Component.text("Only good for the furnace now",
                    net.kyori.adventure.text.format.NamedTextColor.GRAY)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
            m.setMaxStackSize(64);
            m.getPersistentDataContainer().set(FUEL, PersistentDataType.STRING, letter ? "letter" : "box");
        });
        return fuel;
    }

    /** Whatever is packed in a parcel or clipped to a letter. */
    private List<ItemStack> insides(ItemStack item) {
        List<ItemStack> out = new ArrayList<>();
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        List<String> data = new ArrayList<>();
        List<String> list = pdc.get(PAYLOADS, PersistentDataType.LIST.strings());
        if (list != null) data.addAll(list);
        else if (pdc.has(PAYLOAD, PersistentDataType.STRING)) data.add(pdc.get(PAYLOAD, PersistentDataType.STRING));
        if (pdc.has(ATTACHMENT, PersistentDataType.STRING)) data.add(pdc.get(ATTACHMENT, PersistentDataType.STRING));
        for (String d : data) {
            ItemStack it = post.decode(d);
            if (it != null && !it.isEmpty()) out.add(it);
        }
        return out;
    }

    // ---------------- campfire / torch / lava ----------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUse(PlayerInteractEvent e) {
        if (e.useItemInHand() == org.bukkit.event.Event.Result.DENY) return; // another plugin handled it
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_AIR) return;
        if (!plugin.getConfig().getBoolean("burning.enabled", true)) return;
        Player p = e.getPlayer();
        ItemStack item = e.getItem();
        String kind = kind(item);
        if (kind == null) return;
        Location fire = fireAt(p, e.getClickedBlock());
        if (fire == null) return;

        // Both hands fire an event; burn once per click.
        Integer last = lastBurn.put(p.getUniqueId(), Bukkit.getCurrentTick());
        e.setCancelled(true);
        post.skipThisClick(p);
        if (last != null && last == Bukkit.getCurrentTick()) return;

        EquipmentSlot hand = e.getHand() == null ? EquipmentSlot.HAND : e.getHand();
        ItemStack held = p.getInventory().getItem(hand);
        if (kind(held) == null) return;
        List<ItemStack> inside = insides(held);
        // It turns into Ashes in the same slot (or in your inventory if the rest of the stack is still there).
        if (held.getAmount() > 1) {
            held.setAmount(held.getAmount() - 1);
            give(p, Ashes.item(1));
        } else {
            p.getInventory().setItem(hand, Ashes.item(1));
        }

        sizzle(fire);
        // Nothing inside is lost: it pops out into your inventory (or at your feet if it's full).
        for (ItemStack it : inside) give(p, it);
        // An open letter is a Book & Quill: the game opens its screen by itself, so shut it again.
        if (kind.equals("letter")) Bukkit.getScheduler().runTask(plugin, () -> p.closeInventory());
        plugin.msg(p, inside.isEmpty() ? "burned" : "burned-kept",
                "item", kind.contains("letter") ? "letter" : "box");
    }

    private static void give(Player p, ItemStack item) {
        for (ItemStack extra : p.getInventory().addItem(item).values()) p.getWorld().dropItemNaturally(p.getLocation(), extra);
    }

    /** The fire the player is pointing at: a lit campfire, a torch, fire or lava. */
    private Location fireAt(Player p, Block clicked) {
        // Lava (and fire) can't be clicked directly, so look along the player's line of sight.
        RayTraceResult hit = p.rayTraceBlocks(5, FluidCollisionMode.ALWAYS);
        Block b = hit != null && hit.getHitBlock() != null ? hit.getHitBlock() : clicked;
        if (b == null) return null;
        if (isFire(b)) return b.getLocation().add(0.5, 0.5, 0.5);
        Block above = b.getRelative(org.bukkit.block.BlockFace.UP);
        if (above.getType() == Material.FIRE || above.getType() == Material.SOUL_FIRE) return above.getLocation().add(0.5, 0.3, 0.5);
        return null;
    }

    private static boolean isFire(Block b) {
        Material m = b.getType();
        if (m == Material.LAVA || m == Material.FIRE || m == Material.SOUL_FIRE) return true;
        if (m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE) return b.getBlockData() instanceof Lightable l && l.isLit();
        String name = m.name();
        return name.endsWith("TORCH") && !name.contains("REDSTONE");
    }

    private static void sizzle(Location at) {
        var w = at.getWorld();
        w.playSound(at, Sound.BLOCK_FIRE_EXTINGUISH, 0.7f, 1.3f);
        w.playSound(at, Sound.ITEM_FIRECHARGE_USE, 0.4f, 1.6f);
        w.spawnParticle(Particle.FLAME, at, 8, 0.15, 0.15, 0.15, 0.02);
        w.spawnParticle(Particle.LARGE_SMOKE, at, 6, 0.15, 0.2, 0.15, 0.02);
        w.spawnParticle(Particle.WHITE_ASH, at.clone().add(0, 0.4, 0), 40, 0.3, 0.4, 0.3, 0.02);
        w.spawnParticle(Particle.ASH, at.clone().add(0, 0.4, 0), 30, 0.3, 0.4, 0.3, 0.02);
    }

    // ---------------- furnace fuel ----------------

    private int fuelTicks(String kind) {
        return plugin.getConfig().getInt(kind.contains("letter") ? "burning.letter-fuel-ticks" : "burning.box-fuel-ticks",
                kind.contains("letter") ? 100 : 300);
    }

    /**
     * The game won't put paper in the fuel slot, so mail is placed there by hand. Letters put in the
     * top slot of a furnace become "Letters to Burn", which the furnace cooks into charcoal.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) // e.g. StickyBags already put it in a bag
    public void onFurnaceClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory() instanceof FurnaceInventory furnace)) return;
        Player p = (Player) e.getWhoClicked();
        int raw = e.getRawSlot();
        boolean click = e.getClick() == ClickType.LEFT || e.getClick() == ClickType.RIGHT;
        boolean fuelOn = plugin.getConfig().getBoolean("burning.furnace-fuel", true);
        boolean ashes = ashesOn() && furnace.getType() == InventoryType.FURNACE;
        int slot = -1;
        ItemStack moving = null;
        if (click && raw == 0 && ashes && kind(e.getCursor()) != null) {
            slot = 0;
            moving = e.getCursor();
        } else if (click && raw == 1 && fuelOn && kind(e.getCursor()) != null) {
            slot = 1;
            moving = e.getCursor();
        } else if (e.isShiftClick() && raw >= furnace.getSize() && kind(e.getCurrentItem()) != null) {
            moving = e.getCurrentItem();
            // Like logs: they go to the top to be burned to Ashes, otherwise they're fuel.
            if (ashes && fits(furnace.getItem(0), moving)) slot = 0;
            else if (fuelOn) slot = 1;
        }
        if (slot < 0) {
            Bukkit.getScheduler().runTask(plugin, () -> track(furnace));
            return;
        }
        boolean fromCursor = raw < furnace.getSize();
        ItemStack there = furnace.getItem(slot);
        // Something else in the slot (a bag being hardened, logs, coal...): leave the click alone.
        if (!fits(there, moving)) return;
        e.setCancelled(true);
        if (!insides(moving).isEmpty()) {
            plugin.msg(p, "fuel-not-empty");
            return;
        }
        int have = there == null || there.isEmpty() ? 0 : there.getAmount();
        int n = Math.min(64 - have, fromCursor && e.getClick() == ClickType.RIGHT ? 1 : moving.getAmount());
        if (n <= 0) return;
        furnace.setItem(slot, fuelForm(moving, have + n));
        ItemStack left = moving.clone();
        left.setAmount(moving.getAmount() - n);
        if (fromCursor) p.setItemOnCursor(left.getAmount() > 0 ? left : null);
        else e.setCurrentItem(left.getAmount() > 0 ? left : null);
        track(furnace);
    }

    /** Can this mail go on top of what's in the slot (empty, or the same kind of "to burn" stack)? */
    private static boolean fits(ItemStack there, ItemStack mail) {
        return there == null || there.isEmpty() || (there.isSimilar(fuelForm(mail, 1)) && there.getAmount() < 64);
    }

    private boolean ashesOn() {
        return plugin.getConfig().getBoolean("burning.furnace-ashes", true);
    }

    /** Hoppers feeding mail into a furnace from above: it becomes "Letters/Boxes to Burn" on the way in. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent e) {
        if (!(e.getDestination() instanceof FurnaceInventory f) || f.getType() != InventoryType.FURNACE) return;
        if (!ashesOn() || kind(e.getItem()) == null || !insides(e.getItem()).isEmpty()) return;
        e.setItem(fuelForm(e.getItem(), e.getItem().getAmount()));
    }

    /** Dragging mail across the furnace slots would skip the conversion above: not allowed. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFurnaceDrag(org.bukkit.event.inventory.InventoryDragEvent e) {
        if (!(e.getView().getTopInventory() instanceof FurnaceInventory) || kind(e.getOldCursor()) == null) return;
        for (int raw : e.getRawSlots()) {
            if (raw < 3) {
                e.setCancelled(true);
                return;
            }
        }
    }

    /** Furnace recipes: "Letters to Burn" and "Boxes to Burn" -> Ashes. */
    static List<FurnaceRecipe> ashRecipes() {
        ItemStack letter = new ItemStack(Material.PAPER);
        letter.editMeta(m -> m.getPersistentDataContainer().set(TYPE, PersistentDataType.STRING, "sealed_letter"));
        ItemStack box = new ItemStack(Material.PAPER);
        box.editMeta(m -> m.getPersistentDataContainer().set(TYPE, PersistentDataType.STRING, "empty_box"));
        return List.of(
                new FurnaceRecipe(LETTERS_RECIPE, Ashes.item(1), RecipeChoice.exactChoice(fuelForm(letter, 1)), 0.1f, 200),
                new FurnaceRecipe(BOXES_RECIPE, Ashes.item(1), RecipeChoice.exactChoice(fuelForm(box, 1)), 0.1f, 200));
    }

    @EventHandler
    public void onFurnaceOpen(InventoryOpenEvent e) {
        if (e.getInventory() instanceof FurnaceInventory f) track(f);
    }

    private void track(FurnaceInventory inv) {
        if (kind(inv.getFuel()) == null || inv.getLocation() == null) return;
        furnaces.add(inv.getLocation().toBlockLocation());
        tickFurnaces();
    }

    /** Keeps furnaces with mail fuel burning while they have something to cook. */
    private void tickFurnaces() {
        for (Iterator<Location> it = furnaces.iterator(); it.hasNext(); ) {
            Location loc = it.next();
            if (!loc.isWorldLoaded() || !loc.isChunkLoaded()) continue;
            Block b = loc.getBlock();
            if (!(b.getState(false) instanceof Furnace f)) {
                it.remove();
                continue;
            }
            FurnaceInventory inv = f.getInventory();
            String kind = kind(inv.getFuel());
            if (kind == null) {
                it.remove();
                continue;
            }
            if (f.getBurnTime() > 0 || !canCook(b.getType(), inv)) continue;
            ItemStack fuel = inv.getFuel();
            fuel.setAmount(fuel.getAmount() - 1);
            inv.setFuel(fuel.getAmount() > 0 ? fuel : null);
            f.setBurnTime((short) fuelTicks(kind));
            if (b.getBlockData() instanceof Lightable l && !l.isLit()) {
                l.setLit(true);
                b.setBlockData(l, false);
            }
            loc.getWorld().spawnParticle(Particle.WHITE_ASH, loc.clone().add(0.5, 1.1, 0.5), 10, 0.2, 0.1, 0.2, 0.01);
        }
    }

    /** Is there something in the furnace that this furnace can cook, with room for the result? */
    private boolean canCook(Material furnaceType, FurnaceInventory inv) {
        ItemStack input = inv.getSmelting();
        if (input == null || input.isEmpty()) return false;
        CookingRecipe<?> recipe = recipeFor(furnaceType, input);
        if (recipe == null) return false;
        ItemStack out = inv.getResult();
        if (out == null || out.isEmpty()) return true;
        ItemStack result = recipe.getResult();
        return out.isSimilar(result) && out.getAmount() + result.getAmount() <= out.getMaxStackSize();
    }

    private CookingRecipe<?> recipeFor(Material furnaceType, ItemStack input) {
        String key = furnaceType + ":" + input.getType();
        Optional<CookingRecipe<?>> cached = recipes.get(key);
        if (cached != null && (cached.isEmpty() || cached.get().getInputChoice().test(input))) return cached.orElse(null);
        CookingRecipe<?> found = null;
        for (Iterator<Recipe> it = Bukkit.recipeIterator(); it.hasNext(); ) {
            if (!(it.next() instanceof CookingRecipe<?> r) || !forFurnace(furnaceType, r)) continue;
            if (r.getInputChoice().test(input)) {
                found = r;
                break;
            }
        }
        recipes.put(key, Optional.ofNullable(found));
        return found;
    }

    private static boolean forFurnace(Material type, CookingRecipe<?> r) {
        return switch (type) {
            case SMOKER -> r instanceof SmokingRecipe;
            case BLAST_FURNACE -> r instanceof BlastingRecipe;
            default -> r instanceof FurnaceRecipe;
        };
    }
}

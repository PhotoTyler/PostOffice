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
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.SmokingRecipe;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;

/**
 * Getting rid of old mail:
 * <ul>
 *   <li>Right-click a lit campfire, a torch or lava with a letter or box: it sizzles away into ash.
 *       Anything inside a parcel (or clipped to a letter) pops out first, so nothing is lost.</li>
 *   <li>Letters and boxes burn as furnace fuel (furnace, smoker, blast furnace). The game doesn't
 *       count paper as fuel, so the fuel slot is filled and burned by this class.</li>
 * </ul>
 */
final class Burning implements Listener {
    private static final NamespacedKey TYPE = new NamespacedKey("postoffice", "type");
    private static final NamespacedKey PAYLOADS = new NamespacedKey("postoffice", "payloads");
    private static final NamespacedKey PAYLOAD = new NamespacedKey("postoffice", "payload");
    private static final NamespacedKey ATTACHMENT = new NamespacedKey("postoffice", "attachment");
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
        String k = item.getItemMeta().getPersistentDataContainer().get(TYPE, PersistentDataType.STRING);
        return k != null && BURNABLE.contains(k) ? k : null;
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
        if (held.getAmount() > 1) held.setAmount(held.getAmount() - 1);
        else p.getInventory().setItem(hand, null);

        sizzle(fire);
        // Nothing inside is lost: it pops out into your inventory (or at your feet if it's full).
        for (ItemStack it : inside) {
            for (ItemStack extra : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), extra);
        }
        // An open letter is a Book & Quill: the game opens its screen by itself, so shut it again.
        if (kind.equals("letter")) Bukkit.getScheduler().runTask(plugin, () -> p.closeInventory());
        plugin.msg(p, inside.isEmpty() ? "burned" : "burned-kept",
                "item", kind.contains("letter") ? "letter" : "box");
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

    /** The game won't put paper in the fuel slot, so mail is placed there by hand. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onFurnaceClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory() instanceof FurnaceInventory furnace)) return;
        if (!plugin.getConfig().getBoolean("burning.furnace-fuel", true)) return;
        Player p = (Player) e.getWhoClicked();
        int raw = e.getRawSlot();
        boolean intoFuel = raw == 1 && (e.getClick() == ClickType.LEFT || e.getClick() == ClickType.RIGHT)
                && kind(e.getCursor()) != null;
        boolean shift = e.isShiftClick() && raw >= furnace.getSize() && kind(e.getCurrentItem()) != null;
        if (!intoFuel && !shift) {
            Bukkit.getScheduler().runTask(plugin, () -> track(furnace));
            return;
        }
        ItemStack moving = intoFuel ? e.getCursor() : e.getCurrentItem();
        e.setCancelled(true);
        if (!insides(moving).isEmpty()) {
            plugin.msg(p, "fuel-not-empty");
            return;
        }
        ItemStack fuel = furnace.getFuel();
        int room;
        if (fuel == null || fuel.isEmpty()) room = moving.getMaxStackSize();
        else if (fuel.isSimilar(moving)) room = fuel.getMaxStackSize() - fuel.getAmount();
        else if (intoFuel) {
            // Different fuel already there: swap them, like the game does.
            ItemStack old = fuel.clone();
            furnace.setFuel(moving.clone());
            p.setItemOnCursor(old);
            track(furnace);
            return;
        } else return;
        int n = Math.min(room, intoFuel && e.getClick() == ClickType.RIGHT ? 1 : moving.getAmount());
        if (n <= 0) return;
        ItemStack put = moving.clone();
        put.setAmount((fuel == null || fuel.isEmpty() ? 0 : fuel.getAmount()) + n);
        furnace.setFuel(put);
        ItemStack left = moving.clone();
        left.setAmount(moving.getAmount() - n);
        if (intoFuel) p.setItemOnCursor(left.getAmount() > 0 ? left : null);
        else e.setCurrentItem(left.getAmount() > 0 ? left : null);
        track(furnace);
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

package click.portl.postoffice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.VillagerCareerChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataType;

/**
 * Village Postmen: an unemployed villager standing next to a drop-off postbox takes the job (like a
 * workstation), puts on the uniform and cap, and trades paper, ink, letters, boxes and mailboxes.
 * The Post Office Postmaster trades the same way.
 */
final class Traders implements Listener {
    private final PostOfficePlugin plugin;
    private final Set<UUID> known = new HashSet<>();
    private final Map<UUID, Long> chatCooldown = new HashMap<>();

    Traders(PostOfficePlugin plugin) {
        this.plugin = plugin;
    }

    static boolean isTrader(Entity e) {
        return e instanceof Villager && e.getPersistentDataContainer().has(Keys.TRADER);
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::recruit, 100L, 100L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::turnCaps, 2L, 2L);
        for (var w : Bukkit.getWorlds()) for (Villager v : w.getEntitiesByClass(Villager.class)) if (isTrader(v)) known.add(v.getUniqueId());
    }

    void track(Villager v) {
        known.add(v.getUniqueId());
    }

    /** Unemployed villagers next to a drop-off become Postmen. */
    private void recruit() {
        if (!plugin.getConfig().getBoolean("village-postmen.enabled", true)) return;
        double r = plugin.getConfig().getDouble("village-postmen.radius", 3);
        for (PostBlock pb : plugin.blocks().all()) {
            if (pb.type != PostBlock.Type.DROPOFF || !pb.isLoaded()) continue;
            for (Entity e : pb.bukkitWorld().getNearbyEntities(pb.center(), r, 2, r)) {
                if (!(e instanceof Villager v) || isTrader(v) || plugin.post().isPostmanEntity(v)) continue;
                if (!v.isAdult() || v.getProfession() != Villager.Profession.NONE) continue;
                hire(v);
            }
        }
    }

    private void hire(Villager v) {
        Villager.Profession nitwit = Villager.Profession.NITWIT;
        v.setProfession(nitwit);
        v.customName(Msg.item(plugin.getConfig().getString("village-postmen.name", "<gold>Postman")));
        v.setCustomNameVisible(false);
        v.getPersistentDataContainer().set(Keys.TRADER, PersistentDataType.BOOLEAN, true);
        Postman.addCap(plugin, v, Keys.TRADER, v.getUniqueId().toString(), true);
        known.add(v.getUniqueId());
        v.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, v.getLocation().add(0, 1.8, 0), 10, 0.3, 0.3, 0.3);
        v.getWorld().playSound(v.getLocation(), Sound.ENTITY_VILLAGER_WORK_CARTOGRAPHER, 1f, 1f);
    }

    /** The cap is a passenger, so it doesn't turn with the head by itself. */
    private void turnCaps() {
        known.removeIf(id -> {
            Entity e = Bukkit.getEntity(id);
            if (e == null) return false; // unloaded; keep
            if (!e.isValid()) return true;
            for (Entity p : e.getPassengers()) {
                if (p instanceof ItemDisplay d && e instanceof Villager v) d.setRotation(v.getBodyYaw(), 0f);
            }
            return false;
        });
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) if (isTrader(en)) known.add(en.getUniqueId());
    }

    // ---------------- trading ----------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (!isTrader(e.getRightClicked())) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        Villager v = (Villager) e.getRightClicked();
        if (v.getPersistentDataContainer().has(Keys.CLERK)) greet(p);
        Merchant m = Bukkit.createMerchant(v.customName() != null ? v.customName() : Msg.item("Postman"));
        m.setRecipes(recipes());
        p.openMerchant(m, true);
        v.getWorld().playSound(v.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 1f, 1f);
    }

    private void greet(Player p) {
        long now = System.currentTimeMillis();
        Long last = chatCooldown.get(p.getUniqueId());
        if (last != null && now - last < 30_000) return;
        chatCooldown.put(p.getUniqueId(), now);
        List<String> lines = plugin.getConfig().getStringList("post-offices.clerk-dialogue");
        if (lines.isEmpty()) return;
        String name = plugin.getConfig().getString("post-offices.clerk-name", "Postmaster");
        p.sendMessage(Msg.parse("<gold>[</gold>" + name + "<gold>]</gold> <white>").append(
                Msg.parse(lines.get(ThreadLocalRandom.current().nextInt(lines.size())), "player", p.getName())));
    }

    /** Trades from config: "cost" and "result" are "MATERIAL amount" or "postoffice:<item> amount". */
    List<MerchantRecipe> recipes() {
        List<MerchantRecipe> out = new ArrayList<>();
        for (Map<?, ?> t : plugin.getConfig().getMapList("village-postmen.trades")) {
            ItemStack cost = parse(String.valueOf(t.get("cost")));
            ItemStack cost2 = t.containsKey("cost2") ? parse(String.valueOf(t.get("cost2"))) : null;
            ItemStack result = parse(String.valueOf(t.get("result")));
            if (cost == null || result == null) continue;
            MerchantRecipe r = new MerchantRecipe(result, 0, Integer.MAX_VALUE, false);
            r.addIngredient(cost);
            if (cost2 != null) r.addIngredient(cost2);
            out.add(r);
        }
        return out;
    }

    static ItemStack parse(String spec) {
        String[] parts = spec.trim().split("\\s+");
        int amount = parts.length > 1 ? Math.max(1, Integer.parseInt(parts[1])) : 1;
        ItemStack item;
        if (parts[0].toLowerCase().startsWith("postoffice:")) {
            MailKind kind = MailKind.byId(parts[0].substring("postoffice:".length()).toLowerCase());
            if (kind == null) return null;
            item = kind == MailKind.LETTER ? MailItems.blankLetter() : MailItems.create(kind);
        } else {
            Material m = Material.matchMaterial(parts[0]);
            if (m == null || !m.isItem()) return null;
            item = new ItemStack(m);
        }
        item.setAmount(Math.min(amount, item.getMaxStackSize()));
        return item;
    }

    // Keep them Postmen: no job changes, and a zombie cure makes a normal villager anyway.
    @EventHandler(priority = EventPriority.HIGH)
    public void onCareer(VillagerCareerChangeEvent e) {
        if (isTrader(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler
    public void onTransform(EntityTransformEvent e) {
        if (!isTrader(e.getEntity())) return;
        for (Entity p : e.getEntity().getPassengers()) if (p instanceof ItemDisplay) p.remove();
        known.remove(e.getEntity().getUniqueId());
    }
}

package click.portl.postoffice;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.event.entity.VillagerCareerChangeEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Talking to the Postman, keeping him a Postman, and what happens when somebody kills him. */
final class PostmanListener implements Listener {
    private final PostOfficePlugin plugin;
    private final Map<UUID, Long> chatCooldown = new HashMap<>();

    PostmanListener(PostOfficePlugin plugin) {
        this.plugin = plugin;
    }

    private boolean isPostman(Entity e) {
        return plugin.post().isPostmanEntity(e);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (!isPostman(e.getRightClicked())) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        long now = System.currentTimeMillis();
        Long last = chatCooldown.get(p.getUniqueId());
        if (last != null && now - last < 2000) return;
        chatCooldown.put(p.getUniqueId(), now);
        List<String> lines = plugin.getConfig().getStringList("postman.dialogue");
        if (lines.isEmpty()) return;
        Msg.postman(p, lines.get(ThreadLocalRandom.current().nextInt(lines.size())), "player", p.getName());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractAt(PlayerInteractAtEntityEvent e) {
        if (isPostman(e.getRightClicked())) e.setCancelled(true);
    }

    /** Only players can hurt him (and only if the config allows it). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        boolean clerk = e.getEntity().getPersistentDataContainer().has(Keys.CLERK);
        if (!isPostman(e.getEntity()) && !clerk) return;
        if (!plugin.getConfig().getBoolean("postman.killable", true)) {
            e.setCancelled(true);
            return;
        }
        if (e instanceof EntityDamageByEntityEvent by) {
            Entity d = by.getDamager();
            if (d instanceof Player) return;
            if (d instanceof Projectile proj && proj.getShooter() instanceof Player) return;
        }
        e.setCancelled(true);
    }

    /** The Postmaster can be hurt, but stays put behind his counter. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onKnockback(io.papermc.paper.event.entity.EntityKnockbackEvent e) {
        if (e.getEntity().getPersistentDataContainer().has(Keys.CLERK)) e.setCancelled(true);
    }

    /** Delivery Postman: his bag spills (only the senders are told), and his cap drops. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent e) {
        if (!isPostman(e.getEntity())) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        dropCap(e);
        Postman p = plugin.post().postmanOf(e.getEntity());
        if (p == null) return;
        e.getDrops().addAll(plugin.post().spill(p, e.getEntity().getKiller()));
        p.killed();
    }

    /** Postmaster or a village Postman: the floating cap comes off and drops as a wearable cap. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onOtherPostmanDeath(EntityDeathEvent e) {
        var pdc = e.getEntity().getPersistentDataContainer();
        if (isPostman(e.getEntity()) || !(pdc.has(Keys.CLERK) || pdc.has(Keys.TRADER))) return;
        dropCap(e);
        String village = pdc.get(Keys.CLERK, org.bukkit.persistence.PersistentDataType.STRING);
        if (village != null) plugin.offices().clerkDied(village);
    }

    private void dropCap(EntityDeathEvent e) {
        boolean hadCap = false;
        for (Entity passenger : e.getEntity().getPassengers()) {
            if (passenger instanceof org.bukkit.entity.ItemDisplay) {
                passenger.remove();
                hadCap = true;
            }
        }
        if (hadCap && plugin.getConfig().getBoolean("postman.drop-cap", true)) e.getDrops().add(MailItems.postmanCap());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onTransform(EntityTransformEvent e) {
        if (isPostman(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCareer(VillagerCareerChangeEvent e) {
        if (isPostman(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onTrade(VillagerAcquireTradeEvent e) {
        if (isPostman(e.getEntity())) e.setCancelled(true);
    }

    /** Leftover Postmen (e.g. after a crash) are cleaned up; their mail is still in the queue. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            if (isPostman(en) && plugin.post().postmanOf(en) == null) en.remove();
        }
    }
}

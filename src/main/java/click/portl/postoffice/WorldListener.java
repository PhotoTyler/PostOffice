package click.portl.postoffice;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Clicks on mailboxes, drop-offs and parcels, using PostOffice items, joining, chunk loads. */
final class WorldListener implements Listener {
    private final PostOfficePlugin plugin;

    WorldListener(PostOfficePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        Action action = e.getAction();
        Block block = e.getClickedBlock();

        PostBlock pb = plugin.blocks().at(block);
        PlacedParcel pp = pb == null ? plugin.parcels().at(block) : null;
        if (pb != null || pp != null) {
            e.setCancelled(true);
            if (e.getHand() != EquipmentSlot.HAND) return;
            if (action == Action.RIGHT_CLICK_BLOCK) {
                if (pb != null) plugin.blocks().rightClick(p, pb, EquipmentSlot.HAND);
                else plugin.parcels().rightClick(p, pp);
            } else if (action == Action.LEFT_CLICK_BLOCK) {
                if (pb != null) plugin.blocks().tryBreak(p, pb);
                else plugin.parcels().breakParcel(pp);
            }
            return;
        }

        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = e.getItem();
        MailKind kind = MailItems.kind(item);
        if (kind == null) return;
        EquipmentSlot hand = e.getHand();
        boolean onBlock = action == Action.RIGHT_CLICK_BLOCK && block != null;
        boolean canPlaceHere = onBlock && (!block.getType().isInteractable() || p.isSneaking());

        switch (kind) {
            case MAILBOX_UK, MAILBOX_US, DROPOFF -> {
                if (!canPlaceHere) return;
                e.setCancelled(true);
                plugin.blocks().place(p, block, e.getBlockFace(), item, hand);
            }
            case PARCEL -> {
                if (onBlock && !canPlaceHere) return;
                e.setCancelled(true);
                if (onBlock) plugin.parcels().place(p, block, e.getBlockFace(), item, hand);
                else Msg.send(p, "parcel.hint");
            }
            case LETTER, SEALED_LETTER -> {
                if (onBlock && !canPlaceHere) return;
                e.setCancelled(true);
                int slot = hand == EquipmentSlot.OFF_HAND ? 40 : p.getInventory().getHeldItemSlot();
                LetterDialog.open(plugin, p, slot);
            }
            default -> {}
        }
    }

    /** Creative players break blocks instantly; treat it like the survival left-click. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent e) {
        PostBlock pb = plugin.blocks().at(e.getBlock());
        if (pb != null) {
            e.setCancelled(true);
            plugin.blocks().tryBreak(e.getPlayer(), pb);
            return;
        }
        PlacedParcel pp = plugin.parcels().at(e.getBlock());
        if (pp != null) {
            e.setCancelled(true);
            plugin.parcels().breakParcel(pp);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            plugin.post().giveReturns(p);
            List<String> waiting = new ArrayList<>();
            for (PostBlock pb : plugin.blocks().mailboxesOf(p.getUniqueId())) {
                int n = plugin.store().mailCount(pb.id);
                if (n > 0) waiting.add(pb.name + " (" + n + ")");
            }
            if (!waiting.isEmpty()) Msg.send(p, "mailbox.join-notice", "mailboxes", String.join(", ", waiting));
        }, 60L);
    }

    /** Rebuild display entities whenever a chunk with PostOffice blocks loads. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        Chunk c = e.getChunk();
        String world = c.getWorld().getName();
        List<PostBlock> blocks = new ArrayList<>();
        for (PostBlock pb : plugin.blocks().all()) {
            if (pb.world.equals(world) && pb.x >> 4 == c.getX() && pb.z >> 4 == c.getZ()) blocks.add(pb);
        }
        List<PlacedParcel> parcels = new ArrayList<>();
        for (PlacedParcel pp : plugin.parcels().all()) {
            if (pp.world.equals(world) && pp.x >> 4 == c.getX() && pp.z >> 4 == c.getZ()) parcels.add(pp);
        }
        if (blocks.isEmpty() && parcels.isEmpty()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!c.isLoaded()) return;
            for (PostBlock pb : blocks) if (plugin.blocks().at(pb.block()) == pb) plugin.blocks().refresh(pb);
            for (PlacedParcel pp : parcels) if (plugin.parcels().at(pp.bukkitWorld().getBlockAt(pp.x, pp.y, pp.z)) == pp) plugin.parcels().refresh(pp);
        });
    }
}

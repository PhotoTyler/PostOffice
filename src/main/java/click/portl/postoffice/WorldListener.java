package click.portl.postoffice;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import org.bukkit.Chunk;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
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
            case SEALED_LETTER -> {
                if (onBlock && !canPlaceHere) return;
                e.setCancelled(true);
                openEnvelope(p, hand);
            }
            case LETTER -> {
                int slot = hand == EquipmentSlot.OFF_HAND ? 40 : p.getInventory().getHeldItemSlot();
                if (item.getType() != MailKind.LETTER.material) {
                    // A paper letter from v1.1.0: turn it back into a book letter.
                    e.setCancelled(true);
                    p.getInventory().setItem(slot, MailItems.unseal(item));
                    return;
                }
                if (p.isSneaking()) {
                    e.setCancelled(true);
                    // Next tick, so our menu replaces the book screen the client may already have opened.
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (MailItems.kind(p.getInventory().getItem(slot)) == MailKind.LETTER) {
                            new LetterMenus.Attach(plugin, p, slot).open(p);
                        }
                    });
                    return;
                }
                if (item.getAmount() > 1) {
                    // Writing on one letter from a stack: split it off so the rest stay blank.
                    ItemStack rest = item.clone();
                    rest.setAmount(item.getAmount() - 1);
                    item.setAmount(1);
                    MailItems.ensureUnique(item);
                    item.editMeta(m -> m.setMaxStackSize(1));
                    p.getInventory().setItem(slot, item);
                    LetterMenus.giveBack(p, rest);
                }
                // Otherwise the normal book editor opens; its Sign button reads "Put in envelope".
            }
            default -> {}
        }
    }

    /** Right-click an envelope: it opens into the letter, shown as a book to read. */
    private void openEnvelope(Player p, EquipmentSlot hand) {
        int slot = hand == EquipmentSlot.OFF_HAND ? 40 : p.getInventory().getHeldItemSlot();
        ItemStack letter = MailItems.unseal(p.getInventory().getItem(slot));
        p.getInventory().setItem(slot, letter);
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 0.8f);
        List<Component> pages = new ArrayList<>();
        for (String page : MailItems.pages(letter)) pages.add(Component.text(page));
        if (pages.isEmpty()) pages.add(Component.empty());
        String from = MailItems.sender(letter);
        p.openBook(Book.book(Component.text("Letter"), Component.text(from == null ? "?" : from), pages));
        Msg.send(p, MailItems.attachment(letter) != null ? "letter.opened-with-item" : "letter.opened");
    }

    /** The pack renames the book's Sign button to "Put in envelope": for letters, sealing replaces signing. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEditBook(PlayerEditBookEvent e) {
        if (!e.isSigning()) return;
        if (!e.getPreviousBookMeta().getPersistentDataContainer().has(Keys.TYPE)) return;
        String title = e.getNewBookMeta().getTitle();
        e.setSigning(false);
        Player p = e.getPlayer();
        int slot = e.getSlot();
        Bukkit.getScheduler().runTask(plugin, () -> {
            ItemStack letter = p.getInventory().getItem(slot);
            if (MailItems.kind(letter) != MailKind.LETTER) return;
            String from = title == null || title.isBlank() ? p.getName() : PostBlocks.trimName(title);
            p.getInventory().setItem(slot, MailItems.seal(letter, from));
            p.playSound(p.getLocation(), Sound.ITEM_BOOK_PUT, 1f, 1.2f);
            Msg.send(p, "letter.sealed");
        });
    }

    // ---------------- shaped hitboxes (invisible shulkers) ----------------

    private boolean useHitbox(Player p, org.bukkit.entity.Entity hitbox, boolean attack) {
        if (!Hitboxes.isHitbox(hitbox)) return false;
        String blockId = Displays.tagOf(hitbox, Keys.BLOCK_ID);
        String parcelId = Displays.tagOf(hitbox, Keys.PARCEL_ID);
        try {
            if (blockId != null) {
                PostBlock pb = plugin.blocks().any(java.util.UUID.fromString(blockId));
                if (pb == null) hitbox.remove();
                else if (attack) plugin.blocks().tryBreak(p, pb);
                else plugin.blocks().rightClick(p, pb, EquipmentSlot.HAND);
            } else if (parcelId != null) {
                PlacedParcel pp = plugin.parcels().byId(java.util.UUID.fromString(parcelId));
                if (pp == null) hitbox.remove();
                else if (attack) plugin.parcels().breakParcel(pp);
                else plugin.parcels().rightClick(p, pp);
            }
        } catch (IllegalArgumentException ignored) {
        }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHitboxClick(org.bukkit.event.player.PlayerInteractEntityEvent e) {
        if (!Hitboxes.isHitbox(e.getRightClicked())) return;
        e.setCancelled(true);
        if (e.getHand() == EquipmentSlot.HAND) useHitbox(e.getPlayer(), e.getRightClicked(), false);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHitboxClickAt(org.bukkit.event.player.PlayerInteractAtEntityEvent e) {
        if (Hitboxes.isHitbox(e.getRightClicked())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHitboxAttack(io.papermc.paper.event.player.PrePlayerAttackEntityEvent e) {
        if (!Hitboxes.isHitbox(e.getAttacked())) return;
        e.setCancelled(true);
        useHitbox(e.getPlayer(), e.getAttacked(), true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHitboxDamage(org.bukkit.event.entity.EntityDamageEvent e) {
        if (Hitboxes.isHitbox(e.getEntity())) e.setCancelled(true);
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

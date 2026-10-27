package click.portl.postoffice;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** Small chest-GUI base: fixed buttons, optional free slots the player may put items in. */
abstract class Menu implements InventoryHolder {
    protected final PostOfficePlugin plugin;
    protected final Inventory inventory;
    private final Map<Integer, Consumer<Player>> buttons = new HashMap<>();

    Menu(PostOfficePlugin plugin, int rows, Component title) {
        this.plugin = plugin;
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    void open(Player p) {
        p.openInventory(inventory);
    }

    void button(int slot, ItemStack icon, Consumer<Player> action) {
        inventory.setItem(slot, icon);
        if (action == null) buttons.remove(slot);
        else buttons.put(slot, action);
    }

    void clearButtons() {
        buttons.clear();
        inventory.clear();
    }

    void fill() {
        ItemStack pane = icon(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inventory.getSize(); i++) if (inventory.getItem(i) == null && !isFreeSlot(i)) inventory.setItem(i, pane);
    }

    boolean isFreeSlot(int slot) {
        return false;
    }

    void onClose(Player p) {}

    /** Clicks on slots without a button. */
    void click(Player p, int slot, boolean shift) {}

    static ItemStack icon(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(m -> {
            m.displayName(Msg.item(name));
            if (lore.length > 0) m.lore(Arrays.stream(lore).map(l -> Msg.item(l)).toList());
        });
        return item;
    }

    static ItemStack icon(ItemStack base, String name, List<Component> lore) {
        ItemStack item = base.clone();
        item.editMeta(m -> {
            m.displayName(Msg.item(name));
            m.lore(lore);
        });
        return item;
    }

    /** Routes inventory events to the open Menu. */
    static final class Events implements Listener {
        @EventHandler(ignoreCancelled = true)
        public void onClick(InventoryClickEvent e) {
            if (!(e.getView().getTopInventory().getHolder(false) instanceof Menu menu)) return;
            int raw = e.getRawSlot();
            int topSize = e.getView().getTopInventory().getSize();
            boolean top = raw >= 0 && raw < topSize;
            if (!top) {
                if (e.isShiftClick() || e.getAction() == InventoryAction.COLLECT_TO_CURSOR) e.setCancelled(true);
                return;
            }
            if (menu.isFreeSlot(raw) && e.getAction() != InventoryAction.COLLECT_TO_CURSOR && !e.isShiftClick()) return;
            if (menu.isFreeSlot(raw) && e.isShiftClick()) return; // shift out of the free slot is fine
            e.setCancelled(true);
            Consumer<Player> action = menu.buttons.get(raw);
            if (!(e.getWhoClicked() instanceof Player p)) return;
            if (action != null) action.accept(p);
            else menu.click(p, raw, e.isShiftClick());
        }

        @EventHandler(ignoreCancelled = true)
        public void onDrag(InventoryDragEvent e) {
            if (!(e.getView().getTopInventory().getHolder(false) instanceof Menu menu)) return;
            int topSize = e.getView().getTopInventory().getSize();
            for (int raw : e.getRawSlots()) {
                if (raw < topSize && !menu.isFreeSlot(raw)) {
                    e.setCancelled(true);
                    return;
                }
            }
        }

        @EventHandler
        public void onClose(InventoryCloseEvent e) {
            if (e.getInventory().getHolder(false) instanceof Menu menu && e.getPlayer() instanceof Player p) menu.onClose(p);
        }
    }
}

package click.portl.postoffice;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * PostOffice: letters, parcels, mailboxes and drop-offs, delivered by a Postman villager.
 */
public final class PostOfficePlugin extends JavaPlugin {
    private DataStore store;
    private PostBlocks blocks;
    private Parcels parcels;
    private PostService post;
    private ChatPrompts prompts;
    private ResourcePack pack;
    private Recipes recipes;
    private Traders traders;
    private PostOffices offices;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Keys.init(this);
        Msg.init(this);
        MailItems.init(this);

        store = new DataStore(this);
        store.load();
        blocks = new PostBlocks(this, store);
        parcels = new Parcels(this, store);
        post = new PostService(this, store);
        prompts = new ChatPrompts(this);
        pack = new ResourcePack(this);
        recipes = new Recipes(this);
        recipes.register();
        traders = new Traders(this);
        offices = new PostOffices(this, store);

        var pm = Bukkit.getPluginManager();
        pm.registerEvents(new WorldListener(this), this);
        pm.registerEvents(new PostmanListener(this), this);
        pm.registerEvents(new Menu.Events(), this);
        pm.registerEvents(prompts, this);
        pm.registerEvents(pack, this);
        pm.registerEvents(recipes, this);
        pm.registerEvents(traders, this);
        pm.registerEvents(offices, this);

        PluginCommand cmd = getCommand("postoffice");
        if (cmd != null) {
            PostCommand handler = new PostCommand(this);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        pack.reload();
        post.start();
        parcels.start();
        traders.start();
        offices.startChecks();

        // Leftover Postmen from a crash in already-loaded chunks; refresh displays near loaded blocks.
        Bukkit.getScheduler().runTask(this, () -> {
            for (var w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) {
                if (post.isPostmanEntity(e)) e.remove();
            }
            for (PostBlock pb : blocks.all()) if (pb.isLoaded()) blocks.refresh(pb);
            for (PlacedParcel pp : parcels.all()) parcels.refresh(pp);
            offices.scanLoaded();
        });
        getLogger().info("PostOffice ready: " + store.blocks.size() + " boxes, " + store.deliveries.size() + " letters/parcels in the post.");
    }

    @Override
    public void onDisable() {
        if (post != null) post.stop();
        if (recipes != null) recipes.unregister();
        if (store != null) store.close();
    }

    DataStore store() {
        return store;
    }

    PostBlocks blocks() {
        return blocks;
    }

    Parcels parcels() {
        return parcels;
    }

    PostService post() {
        return post;
    }

    ChatPrompts prompts() {
        return prompts;
    }

    PostOffices offices() {
        return offices;
    }

    Traders traders() {
        return traders;
    }

    ResourcePack pack() {
        return pack;
    }
}

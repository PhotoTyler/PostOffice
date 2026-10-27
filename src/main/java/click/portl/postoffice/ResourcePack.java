package click.portl.postoffice;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Sends the texture pack to players on join. The SHA-1 is worked out by downloading the pack once at
 * startup (and on /postoffice reload), so updating the pack on GitHub only needs a reload.
 */
final class ResourcePack implements Listener {
    private final PostOfficePlugin plugin;
    private volatile String url = "";
    private volatile String hash = "";
    private volatile String problem; // why the pack can't be downloaded, shown to ops on join
    // Early builds pointed here, but the pack is actually served from github.io.
    private static final String OLD_URL = "https://portl.click/PostOffice/PostOffice-pack.zip";
    static final String DEFAULT_URL = "https://phototyler.github.io/PostOffice/PostOffice-pack.zip";

    ResourcePack(PostOfficePlugin plugin) {
        this.plugin = plugin;
    }

    void reload() {
        if (OLD_URL.equals(plugin.getConfig().getString("resource-pack.url", "").trim())) {
            plugin.getConfig().set("resource-pack.url", DEFAULT_URL);
            plugin.saveConfig();
            plugin.getLogger().info("Updated resource-pack.url to " + DEFAULT_URL);
        }
        problem = null;
        url = plugin.getConfig().getString("resource-pack.url", "").trim();
        hash = plugin.getConfig().getString("resource-pack.sha1", "").trim().toLowerCase();
        if (url.isEmpty()) {
            plugin.getLogger().warning("resource-pack.url is empty: players won't see the PostOffice textures.");
            return;
        }
        if (!hash.isEmpty()) return;
        String target = url;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofSeconds(15)).build();
                HttpResponse<InputStream> res = client.send(HttpRequest.newBuilder(URI.create(target))
                        .header("Cache-Control", "no-cache").timeout(Duration.ofSeconds(60)).build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode() + " from " + target);
                MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
                try (InputStream in = res.body()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) sha1.update(buf, 0, n);
                }
                if (target.equals(url)) {
                    hash = HexFormat.of().formatHex(sha1.digest());
                    plugin.getLogger().info("Resource pack ready (SHA-1 " + hash + ")");
                }
            } catch (Exception e) {
                problem = e.getMessage();
                plugin.getLogger().warning("Couldn't download the resource pack (" + e.getMessage()
                        + "). Players will see pink/black boxes until resource-pack.url in config.yml is fixed.");
            }
        });
    }

    void send(Player p) {
        if (url.isEmpty()) return;
        UUID id = UUID.nameUUIDFromBytes(("postoffice:" + url).getBytes(StandardCharsets.UTF_8));
        ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(id, URI.create(url), hash);
        p.sendResourcePacks(ResourcePackRequest.resourcePackRequest()
                .packs(info)
                .required(plugin.getConfig().getBoolean("resource-pack.required", false))
                .prompt(Msg.get("resource-pack.prompt"))
                .replace(false)
                .build());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!plugin.getConfig().getBoolean("resource-pack.send-on-join", true)) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            send(p);
            if (problem != null && p.hasPermission("postoffice.admin")) {
                Msg.send(p, "resource-pack.broken", "error", problem);
            }
        }, 20L);
    }
}

package click.portl.mysterymail;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Jokes: a fresh one from a joke website each time (off the main thread), or one from the backup
 * list in config.yml when the site can't be reached.
 */
final class Jokes {
    /** A joke. {@code punchline} is null for one-liners. */
    record Joke(String setup, String punchline) {}

    private final MysteryMail plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final Deque<String> recent = new ArrayDeque<>(); // so the same joke doesn't come twice in a row

    Jokes(MysteryMail plugin) {
        this.plugin = plugin;
    }

    /** Completes off the main thread; never fails (falls back to the backup list). */
    CompletableFuture<Joke> next() {
        if (!plugin.getConfig().getBoolean("jokes.fetch-online", true)) return CompletableFuture.completedFuture(backup());
        return fetch().thenCompose(j -> j != null && isRecent(j) ? fetch() : CompletableFuture.completedFuture(j))
                .exceptionally(err -> {
                    plugin.getLogger().warning("Couldn't fetch a joke (" + err.getClass().getSimpleName()
                            + "): using a backup joke.");
                    return null;
                })
                .thenApply(j -> {
                    Joke joke = j != null ? j : backup();
                    remember(joke);
                    return joke;
                });
    }

    private CompletableFuture<Joke> fetch() {
        String url = plugin.getConfig().getString("jokes.url", "https://icanhazdadjoke.com/");
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(plugin.getConfig().getInt("jokes.timeout-seconds", 5)))
                .header("Accept", "application/json")
                // icanhazdadjoke asks for a User-Agent that says who's calling
                .header("User-Agent", "MysteryMail Minecraft plugin (https://github.com/PhotoTyler/PostOffice)")
                .GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(res -> {
            if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode());
            return parse(res.body());
        });
    }

    /** {"joke": "..."} (icanhazdadjoke) or {"setup": "...", "punchline": "..."} (and lists of those). */
    static Joke parse(String body) {
        JsonElement root = JsonParser.parseString(body);
        if (root.isJsonArray() && !root.getAsJsonArray().isEmpty()) root = root.getAsJsonArray().get(0);
        if (!root.isJsonObject()) throw new IllegalStateException("not a joke");
        JsonObject o = root.getAsJsonObject();
        if (o.has("setup") && o.has("punchline")) {
            return new Joke(clean(o.get("setup").getAsString()), clean(o.get("punchline").getAsString()));
        }
        if (o.has("setup") && o.has("delivery")) { // JokeAPI two-parters
            return new Joke(clean(o.get("setup").getAsString()), clean(o.get("delivery").getAsString()));
        }
        if (o.has("joke")) return split(clean(o.get("joke").getAsString()));
        throw new IllegalStateException("not a joke");
    }

    /** "Why did...? Because..." → question on one page, answer on the next. */
    static Joke split(String text) {
        int q = text.indexOf("? ");
        if (q > 0 && q < text.length() - 2) return new Joke(text.substring(0, q + 1).trim(), text.substring(q + 2).trim());
        return new Joke(text, null);
    }

    private static String clean(String s) {
        return s.replace("\r\n", "\n").replace('\r', '\n').replace('§', ' ').trim();
    }

    private synchronized Joke backup() {
        List<String> list = plugin.getConfig().getStringList("jokes.backup");
        if (list.isEmpty()) return new Joke("Why did the Postman bring an empty letter?", "He ran out of jokes!");
        String pick = null;
        for (int tries = 0; tries < 5; tries++) {
            pick = list.get(ThreadLocalRandom.current().nextInt(list.size()));
            if (!recent.contains(pick.split("\\|", 2)[0])) break;
        }
        String[] parts = pick.split("\\|", 2);
        return new Joke(parts[0].trim(), parts.length > 1 ? parts[1].trim() : null);
    }

    private synchronized boolean isRecent(Joke j) {
        return recent.contains(j.setup());
    }

    private synchronized void remember(Joke j) {
        recent.remove(j.setup());
        recent.addLast(j.setup());
        while (recent.size() > 100) recent.removeFirst();
    }
}

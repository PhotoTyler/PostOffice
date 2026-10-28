package click.portl.postoffice;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.generator.structure.StructurePiece;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;

/**
 * Village Post Offices: a public drop-off postbox, a counter and a Postmaster villager who trades.
 * Spots are picked in a gap between the village's own buildings and streets, on fairly flat ground,
 * with the door facing the nearest street.
 *
 * <p>When they're built by themselves ({@code post-offices.auto-build}): by default only on a
 * brand-new install, for the first village found. After that nothing is ever built automatically
 * (an update or restart reconnects to what's there); admins use /postoffice build. Every village
 * that has had one is remembered in villages.yml, on the world itself and on the chunk, so it is
 * never built twice even if a save is lost.
 */
final class PostOffices implements Listener {
    /** Owner of drop-offs the plugin builds: only admins can remove them. */
    static final UUID SERVER = new UUID(0L, 0L);

    record Office(String village, String world, double x, double y, double z, float yaw) {}

    /** Building materials per village style. */
    private record Palette(Material pillar, Material wall, Material counter, Material stairs, Material roof,
                           Material slab, Material door, Material sign, Material foundation, Material floor,
                           Material path, Material fence) {}

    private static final Palette PLAINS = new Palette(Material.OAK_LOG, Material.OAK_PLANKS, Material.STRIPPED_OAK_LOG,
            Material.OAK_STAIRS, Material.OAK_PLANKS, Material.OAK_SLAB, Material.OAK_DOOR, Material.OAK_WALL_SIGN,
            Material.COBBLESTONE, Material.SPRUCE_PLANKS, Material.DIRT_PATH, Material.OAK_FENCE);
    private static final Palette DESERT = new Palette(Material.CUT_SANDSTONE, Material.SMOOTH_SANDSTONE,
            Material.CUT_SANDSTONE, Material.SMOOTH_SANDSTONE_STAIRS, Material.SMOOTH_SANDSTONE,
            Material.SMOOTH_SANDSTONE_SLAB, Material.JUNGLE_DOOR, Material.JUNGLE_WALL_SIGN, Material.SANDSTONE,
            Material.CUT_SANDSTONE, Material.SMOOTH_SANDSTONE, Material.JUNGLE_FENCE);
    private static final Palette SAVANNA = new Palette(Material.ACACIA_LOG, Material.ACACIA_PLANKS,
            Material.STRIPPED_ACACIA_LOG, Material.ACACIA_STAIRS, Material.ACACIA_PLANKS, Material.ACACIA_SLAB,
            Material.ACACIA_DOOR, Material.ACACIA_WALL_SIGN, Material.COBBLESTONE, Material.OAK_PLANKS, Material.DIRT_PATH,
            Material.ACACIA_FENCE);
    private static final Palette SPRUCE = new Palette(Material.SPRUCE_LOG, Material.SPRUCE_PLANKS,
            Material.STRIPPED_SPRUCE_LOG, Material.SPRUCE_STAIRS, Material.SPRUCE_PLANKS, Material.SPRUCE_SLAB,
            Material.SPRUCE_DOOR, Material.SPRUCE_WALL_SIGN, Material.COBBLESTONE, Material.SPRUCE_PLANKS, Material.DIRT_PATH,
            Material.SPRUCE_FENCE);

    private final PostOfficePlugin plugin;
    private final DataStore store;
    private final Set<String> busy = new HashSet<>();

    PostOffices(PostOfficePlugin plugin, DataStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    private boolean enabledIn(World w) {
        if (!plugin.getConfig().getBoolean("post-offices.enabled", true)) return false;
        List<String> worlds = plugin.getConfig().getStringList("post-offices.worlds");
        return worlds.isEmpty() || worlds.contains(w.getName());
    }

    // ---------------- finding villages ----------------

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        if (!enabledIn(e.getWorld())) return;
        for (GeneratedStructure gs : e.getChunk().getStructures()) consider(e.getWorld(), gs);
    }

    /** Called at startup for chunks that were already loaded. */
    void scanLoaded() {
        for (World w : Bukkit.getWorlds()) {
            if (!enabledIn(w)) continue;
            for (Chunk c : w.getLoadedChunks()) for (GeneratedStructure gs : c.getStructures()) consider(w, gs);
        }
    }

    /** May a Post Office be built by itself right now? See the class comment. */
    private boolean autoBuild() {
        String mode = plugin.getConfig().getString("post-offices.auto-build", "first-install");
        return switch (mode.toLowerCase()) {
            case "every-new-village" -> true;
            case "first-install" -> store.firstInstall && store.offices.isEmpty();
            default -> false;
        };
    }

    private void consider(World w, GeneratedStructure gs) {
        if (!autoBuild()) return;
        NamespacedKey key = RegistryAccess.registryAccess().getRegistry(RegistryKey.STRUCTURE).getKey(gs.getStructure());
        if (key == null || !key.getKey().startsWith("village")) return;
        BoundingBox box = gs.getBoundingBox();
        String id = w.getName() + "|" + key.getKey() + "|" + (int) box.getMinX() + "," + (int) box.getMinY() + "," + (int) box.getMinZ();
        if (store.villagesDone.contains(id) || markedOnWorld(w, id) || !busy.add(id)) return;
        List<BoundingBox> pieces = new ArrayList<>();
        for (StructurePiece p : gs.getPieces()) pieces.add(p.getBoundingBox());
        Palette palette = paletteFor(key.getKey());
        Bukkit.getScheduler().runTask(plugin, () -> loadAndBuild(w, id, box, pieces, palette));
    }

    private static Palette paletteFor(String key) {
        if (key.contains("desert")) return DESERT;
        if (key.contains("savanna")) return SAVANNA;
        if (key.contains("taiga") || key.contains("snowy")) return SPRUCE;
        return PLAINS;
    }

    /** Loads (async) the chunks around the village centre, then picks a spot and builds. */
    private void loadAndBuild(World w, String id, BoundingBox box, List<BoundingBox> pieces, Palette palette) {
        int cx = (int) box.getCenterX(), cz = (int) box.getCenterZ();
        int r = 40;
        List<CompletableFuture<Chunk>> loads = new ArrayList<>();
        for (int x = (cx - r) >> 4; x <= (cx + r) >> 4; x++) {
            for (int z = (cz - r) >> 4; z <= (cz + r) >> 4; z++) loads.add(w.getChunkAtAsync(x, z));
        }
        CompletableFuture.allOf(loads.toArray(new CompletableFuture[0])).whenComplete((v, err) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    busy.remove(id);
                    if (store.villagesDone.contains(id) || !autoBuild()) return;
                    store.villagesDone.add(id);
                    String existing = officeChunkNear(w, cx, cz, r);
                    if (existing != null) {
                        // There's already a Post Office here (its save was lost): don't build another.
                        plugin.getLogger().info("Village " + id + " already has a Post Office (" + existing + "); not building another.");
                        markOnWorld(w, id);
                    } else if (err != null) {
                        plugin.getLogger().warning("Couldn't load village " + id + ": " + err);
                    } else {
                        Site site = findSite(w, cx, cz, r, pieces);
                        if (site == null) plugin.getLogger().info("No room for a Post Office in village " + id);
                        else build(w, id, site, palette);
                    }
                    store.saveVillages();
                }));
    }

    // ---------------- remembering what's been built ----------------

    private static boolean markedOnWorld(World w, String village) {
        List<String> list = w.getPersistentDataContainer().get(Keys.VILLAGES, PersistentDataType.LIST.strings());
        return list != null && list.contains(village);
    }

    private static void markOnWorld(World w, String village) {
        List<String> list = w.getPersistentDataContainer().get(Keys.VILLAGES, PersistentDataType.LIST.strings());
        List<String> out = list == null ? new ArrayList<>() : new ArrayList<>(list);
        if (out.contains(village)) return;
        out.add(village);
        w.getPersistentDataContainer().set(Keys.VILLAGES, PersistentDataType.LIST.strings(), out);
    }

    /** The village id of a Post Office marked on a loaded chunk near (x, z), or null. */
    private static String officeChunkNear(World w, int x, int z, int r) {
        for (int cx = (x - r) >> 4; cx <= (x + r) >> 4; cx++) {
            for (int cz = (z - r) >> 4; cz <= (z + r) >> 4; cz++) {
                if (!w.isChunkLoaded(cx, cz)) continue;
                String v = w.getChunkAt(cx, cz).getPersistentDataContainer().get(Keys.OFFICE, PersistentDataType.STRING);
                if (v != null) return v;
            }
        }
        return null;
    }

    /** Records existing offices on their world and chunk (offices built before these marks existed). */
    void markExisting() {
        for (Office o : store.offices.values()) {
            World w = Bukkit.getWorld(o.world());
            if (w == null) continue;
            markOnWorld(w, o.village());
            int cx = ((int) Math.floor(o.x())) >> 4, cz = ((int) Math.floor(o.z())) >> 4;
            if (w.isChunkLoaded(cx, cz)) {
                w.getChunkAt(cx, cz).getPersistentDataContainer().set(Keys.OFFICE, PersistentDataType.STRING, o.village());
            }
        }
    }

    /** A Postmaster whose office isn't in the saved data (lost save): take the office back on. */
    void adoptClerk(Entity e) {
        if (!(e instanceof Villager v)) return;
        String village = v.getPersistentDataContainer().get(Keys.CLERK, PersistentDataType.STRING);
        if (village == null || store.offices.containsKey(village)) return;
        Location l = v.getLocation();
        Office o = new Office(village, l.getWorld().getName(), l.getBlockX() + 0.5, l.getY(), l.getBlockZ() + 0.5, l.getYaw());
        store.offices.put(village, o);
        store.villagesDone.add(village);
        markOnWorld(l.getWorld(), village);
        l.getChunk().getPersistentDataContainer().set(Keys.OFFICE, PersistentDataType.STRING, village);
        store.saveVillages();
        plugin.traders().track(v);
        plugin.getLogger().info("Reconnected the Postmaster of " + village + " whose saved data was missing.");
    }

    // ---------------- choosing a spot ----------------

    private record Site(int x0, int z0, int ground, BlockFace door) {}

    private Site findSite(World w, int cx, int cz, int r, List<BoundingBox> pieces) {
        List<int[]> candidates = new ArrayList<>();
        for (int x = cx - r; x <= cx + r - 8; x += 2) {
            for (int z = cz - r; z <= cz + r - 8; z += 2) candidates.add(new int[]{x, z});
        }
        candidates.sort((a, b) -> Double.compare(dist2(a[0] + 4, a[1] + 4, cx, cz), dist2(b[0] + 4, b[1] + 4, cx, cz)));
        for (int[] c : candidates) {
            int x0 = c[0], z0 = c[1];
            if (dist2(x0 + 4, z0 + 4, cx, cz) < 8 * 8) continue; // keep clear of the meeting point
            BoundingBox site = new BoundingBox(x0 - 1, -10000, z0 - 1, x0 + 10, 10000, z0 + 10);
            boolean clash = false;
            BoundingBox nearest = null;
            double nearestDist = Double.MAX_VALUE;
            for (BoundingBox p : pieces) {
                if (p.overlaps(site)) {
                    clash = true;
                    break;
                }
                double d = gap(p, x0, z0);
                if (d < nearestDist) {
                    nearestDist = d;
                    nearest = p;
                }
            }
            if (clash || nearest == null || nearestDist > 4) continue; // must sit inside the village
            Integer ground = flatGround(w, x0, z0);
            if (ground == null) continue;
            return new Site(x0, z0, ground, facing(x0 + 4, z0 + 4, nearest));
        }
        return null;
    }

    private static double dist2(double ax, double az, double bx, double bz) {
        return (ax - bx) * (ax - bx) + (az - bz) * (az - bz);
    }

    /** Horizontal gap between a piece and the 9x9 site. */
    private static double gap(BoundingBox p, int x0, int z0) {
        double dx = Math.max(0, Math.max(p.getMinX() - (x0 + 9), x0 - p.getMaxX()));
        double dz = Math.max(0, Math.max(p.getMinZ() - (z0 + 9), z0 - p.getMaxZ()));
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Ground height of the 9x9 site, or null if it's too bumpy or wet. */
    private static Integer flatGround(World w, int x0, int z0) {
        int[] heights = new int[81];
        int i = 0;
        for (int x = x0; x < x0 + 9; x++) {
            for (int z = z0; z < z0 + 9; z++) {
                int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                Block top = w.getBlockAt(x, y, z);
                if (top.isLiquid() || !top.getType().isSolid()) return null;
                heights[i++] = y;
            }
        }
        Arrays.sort(heights);
        if (heights[80] - heights[0] > 3) return null;
        return heights[40];
    }

    /** Door faces the nearest village piece (usually a street). */
    private static BlockFace facing(int sx, int sz, BoundingBox piece) {
        double dx = piece.getCenterX() - sx, dz = piece.getCenterZ() - sz;
        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? BlockFace.EAST : BlockFace.WEST;
        return dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    // ---------------- building ----------------

    /**
     * Local coordinates: the site is 9x9 (0..8), the house 7x7 (1..7) with its door in the middle of
     * the local south wall (z = 7). {@code site.door} says which world direction local south becomes.
     */
    private final class Builder {
        final World w;
        final Site s;

        Builder(World w, Site s) {
            this.w = w;
            this.s = s;
        }

        Block at(int lx, int y, int lz) {
            int[] r = rotate(lx - 4, lz - 4, s.door);
            return w.getBlockAt(s.x0 + 4 + r[0], s.ground + y, s.z0 + 4 + r[1]);
        }

        BlockFace face(BlockFace local) {
            BlockFace[] order = {BlockFace.SOUTH, BlockFace.WEST, BlockFace.NORTH, BlockFace.EAST};
            int turns = switch (s.door) {
                case WEST -> 1;
                case NORTH -> 2;
                case EAST -> 3;
                default -> 0;
            };
            for (int i = 0; i < 4; i++) if (order[i] == local) return order[(i + turns) % 4];
            return local;
        }

        void set(int lx, int y, int lz, Material m) {
            at(lx, y, lz).setType(m, false);
        }

        void set(int lx, int y, int lz, BlockData d, boolean physics) {
            at(lx, y, lz).setBlockData(d, physics);
        }
    }

    private void build(World w, String id, Site site, Palette p) {
        Builder b = new Builder(w, site);

        // Clear the site and level it: foundation under the house, path around it.
        for (int lx = 0; lx <= 8; lx++) {
            for (int lz = 0; lz <= 8; lz++) {
                for (int y = 1; y <= 12; y++) if (!b.at(lx, y, lz).getType().isAir()) b.set(lx, y, lz, Material.AIR);
                boolean house = lx >= 1 && lx <= 7 && lz >= 1 && lz <= 7;
                b.set(lx, 0, lz, house ? p.foundation : p.path);
                for (int y = -1; y >= -6; y--) {
                    Block below = b.at(lx, y, lz);
                    if (below.getType().isSolid() && !below.isLiquid()) break;
                    below.setType(p.foundation, false);
                }
            }
        }
        // Floor
        for (int lx = 2; lx <= 6; lx++) for (int lz = 2; lz <= 6; lz++) b.set(lx, 0, lz, p.floor);

        // Walls with corner pillars
        for (int y = 1; y <= 4; y++) {
            for (int i = 1; i <= 7; i++) {
                for (int[] c : new int[][]{{i, 1}, {i, 7}, {1, i}, {7, i}}) {
                    boolean corner = (c[0] == 1 || c[0] == 7) && (c[1] == 1 || c[1] == 7);
                    // Post-office red band along the top of the walls, so it stands out
                    b.set(c[0], y, c[1], corner ? p.pillar : y == 4 ? Material.RED_TERRACOTTA : p.wall);
                }
            }
        }

        // Door (local south wall), facing into the house
        Door lower = (Door) p.door.createBlockData();
        lower.setFacing(b.face(BlockFace.NORTH));
        lower.setHalf(Bisected.Half.BOTTOM);
        Door upper = (Door) lower.clone();
        upper.setHalf(Bisected.Half.TOP);
        b.set(4, 1, 7, lower, false);
        b.set(4, 2, 7, upper, false);

        // Windows: panes joined to the wall on both sides
        for (int[] win : new int[][]{{1, 2, 4}, {1, 3, 4}, {7, 2, 4}, {7, 3, 4}, {2, 2, 7}, {6, 2, 7}, {4, 3, 1}}) {
            org.bukkit.block.data.MultipleFacing pane = (org.bukkit.block.data.MultipleFacing) Material.GLASS_PANE.createBlockData();
            boolean sideWall = win[0] == 1 || win[0] == 7; // walls running north-south (local)
            for (BlockFace f : sideWall ? new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH} : new BlockFace[]{BlockFace.EAST, BlockFace.WEST}) {
                pane.setFace(b.face(f), true);
            }
            b.set(win[0], win[1], win[2], pane, false);
        }

        // Roof: stepped pyramid of stairs over a plank ceiling, with a 1-block overhang
        for (int k = 0; k <= 3; k++) {
            int y = 5 + k;
            for (int lx = k; lx <= 8 - k; lx++) {
                for (int lz = k; lz <= 8 - k; lz++) {
                    boolean ring = lx == k || lx == 8 - k || lz == k || lz == 8 - k;
                    if (!ring) {
                        b.set(lx, y, lz, p.roof);
                        continue;
                    }
                    BlockFace up;
                    if (lz == k) up = BlockFace.SOUTH;
                    else if (lz == 8 - k) up = BlockFace.NORTH;
                    else if (lx == k) up = BlockFace.EAST;
                    else up = BlockFace.WEST;
                    Stairs st = (Stairs) p.stairs.createBlockData();
                    st.setFacing(b.face(up));
                    b.set(lx, y, lz, st, true);
                }
            }
        }
        // A red flag on top of the roof
        b.at(4, 9, 4).setType(p.fence, true);
        b.at(4, 10, 4).setType(p.fence, true);
        org.bukkit.block.data.Rotatable flag = (org.bukkit.block.data.Rotatable) Material.RED_BANNER.createBlockData();
        flag.setRotation(b.face(BlockFace.SOUTH));
        b.set(4, 11, 4, flag, false);

        // Counter either side of the postbox, a bookshelf, a lamp and a red carpet to the door
        for (int lx : new int[]{2, 3, 5, 6}) b.set(lx, 1, 3, p.counter);
        b.set(6, 1, 2, Material.BOOKSHELF);
        b.set(6, 2, 2, Material.BOOKSHELF);
        b.set(2, 2, 3, Material.POTTED_POPPY);
        for (int lz = 4; lz <= 6; lz++) b.set(4, 1, lz, Material.RED_CARPET);
        Lantern lamp = (Lantern) Material.LANTERN.createBlockData();
        lamp.setHanging(true);
        b.set(4, 4, 5, lamp, false);
        b.set(2, 4, 2, lamp.clone(), false);

        // Outside: a red awning over the door, a lantern post and a postbox by the door
        for (int lx = 3; lx <= 5; lx++) {
            Stairs awning = (Stairs) Material.RED_NETHER_BRICK_STAIRS.createBlockData();
            awning.setFacing(b.face(BlockFace.NORTH));
            awning.setHalf(Bisected.Half.TOP);
            b.set(lx, 4, 8, awning, false);
        }
        for (int lx : new int[]{2, 6}) { // lantern posts either side of the door
            b.at(lx, 1, 8).setType(p.fence, true);
            b.set(lx, 2, 8, Material.LANTERN.createBlockData(), false);
        }
        for (int lx : new int[]{1, 7}) {
            b.set(lx, 0, 8, Material.GRASS_BLOCK);
            b.set(lx, 1, 8, Material.RED_TULIP);
        }

        // "POST OFFICE" sign over the door, outside
        BlockData signData = p.sign.createBlockData();
        ((Directional) signData).setFacing(b.face(BlockFace.SOUTH));
        Block signBlock = b.at(4, 3, 8);
        signBlock.setBlockData(signData, false);
        if (signBlock.getState() instanceof Sign sign) {
            List<String> lines = plugin.getConfig().getStringList("post-offices.sign");
            for (int i = 0; i < 4 && i < lines.size(); i++) sign.getSide(Side.FRONT).line(i, Msg.parse(lines.get(i)));
            sign.getSide(Side.FRONT).setColor(org.bukkit.DyeColor.RED);
            sign.setWaxed(true);
            sign.update(true, false);
        }

        // The public drop-off: a pillar box behind the counter gap, facing the door
        float yaw = yawOf(b.face(BlockFace.SOUTH));
        Block dropBlock = b.at(4, 1, 2);
        PostBlock drop = plugin.blocks().placeSystem(PostBlock.Type.DROPOFF, dropBlock, yaw, SERVER, "Post Office");

        // The Postmaster behind the counter
        Block clerkBlock = b.at(2, 1, 2);
        Office office = new Office(id, w.getName(), clerkBlock.getX() + 0.5, clerkBlock.getY(), clerkBlock.getZ() + 0.5, yaw);
        store.offices.put(id, office);
        store.villagesDone.add(id);
        markOnWorld(w, id);
        dropBlock.getChunk().getPersistentDataContainer().set(Keys.OFFICE, PersistentDataType.STRING, id);
        clerkBlock.getChunk().getPersistentDataContainer().set(Keys.OFFICE, PersistentDataType.STRING, id);
        if (store.firstInstall) {
            store.firstInstall = false; // from now on, only /postoffice build makes new ones
            store.saveState();
        }
        spawnClerk(office);

        plugin.getLogger().info("Built a Post Office in village " + id + " at " + dropBlock.getX() + ", "
                + dropBlock.getY() + ", " + dropBlock.getZ() + (drop == null ? " (drop-off failed)" : ""));
    }

    /** /postoffice build: a fresh Post Office centred where the player stands, door facing the way they look. */
    void buildHere(org.bukkit.entity.Player p) {
        Location l = p.getLocation();
        World w = l.getWorld();
        BlockFace door = switch (Math.round((((l.getYaw() % 360) + 360) % 360) / 90f) % 4) {
            case 1 -> BlockFace.WEST;
            case 2 -> BlockFace.NORTH;
            case 3 -> BlockFace.EAST;
            default -> BlockFace.SOUTH;
        };
        Site site = new Site(l.getBlockX() - 4, l.getBlockZ() - 4, l.getBlockY() - 1, door);
        String id = w.getName() + "|built|" + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
        String biome = w.getBiome(l).getKey().getKey();
        build(w, id, site, paletteFor(biome.contains("badlands") ? "desert" : biome));
        store.saveVillages();
    }

    boolean hasAnyOffice() {
        return !store.offices.isEmpty();
    }

    /**
     * /postoffice rebuild: every Postmaster removed (with any duplicates or stuck ones) and a fresh one
     * put on his spot. Returns {rebuilt, waiting for their chunk to load}.
     */
    int[] rebuildClerks() {
        // Every Postmaster (and his cap) in loaded chunks goes, wherever he's wandered or got stuck,
        // including duplicates left by older versions.
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                String village = e.getPersistentDataContainer().get(Keys.CLERK, PersistentDataType.STRING);
                if (village != null && store.offices.containsKey(village)) {
                    for (Entity passenger : e.getPassengers()) passenger.remove();
                    e.remove();
                }
            }
        }
        int done = 0, waiting = 0;
        for (Office o : store.offices.values()) {
            World w = Bukkit.getWorld(o.world());
            if (w == null) continue;
            if (!w.isChunkLoaded(((int) Math.floor(o.x())) >> 4, ((int) Math.floor(o.z())) >> 4)) {
                waiting++; // checkClerk sorts him out when the chunk loads
                continue;
            }
            store.clerkDeaths.remove(o.village());
            spawnClerk(o);
            done++;
        }
        store.saveVillages();
        markExisting();
        return new int[]{done, waiting};
    }

    private static int[] rotate(int dx, int dz, BlockFace door) {
        return switch (door) {
            case NORTH -> new int[]{-dx, -dz};
            case EAST -> new int[]{dz, -dx};
            case WEST -> new int[]{-dz, dx};
            default -> new int[]{dx, dz};
        };
    }

    static float yawOf(BlockFace f) {
        return switch (f) {
            case WEST -> 90f;
            case NORTH -> 180f;
            case EAST -> 270f;
            default -> 0f;
        };
    }

    // ---------------- the Postmaster ----------------

    private void spawnClerk(Office o) {
        World w = Bukkit.getWorld(o.world());
        if (w == null) return;
        Location at = new Location(w, o.x(), o.y(), o.z(), o.yaw(), 0f);
        Villager v = w.spawn(at, Villager.class, villager -> {
            Postman.dress(plugin, villager, plugin.getConfig().getString("post-offices.clerk-name", "<gold>Postmaster"), true);
            villager.setCollidable(false); // players can't shove him out from behind the counter
            villager.getPersistentDataContainer().set(Keys.CLERK, PersistentDataType.STRING, o.village());
            villager.getPersistentDataContainer().set(Keys.TRADER, PersistentDataType.BOOLEAN, true);
        });
        Postman.addCap(plugin, v, Keys.CLERK, o.village(), true);
        plugin.traders().track(v);
    }

    /**
     * Makes sure each Post Office has exactly one Postmaster. Entities load separately from (and
     * after) chunks, so this looks a moment later rather than trusting the event's entity list.
     */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        Chunk c = e.getChunk();
        for (Entity en : e.getEntities()) adoptClerk(en);
        for (Office o : store.offices.values()) {
            if (o.world().equals(c.getWorld().getName()) && ((int) Math.floor(o.x())) >> 4 == c.getX()
                    && ((int) Math.floor(o.z())) >> 4 == c.getZ()) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (c.isLoaded()) checkClerk(o);
                }, 40L);
            }
        }
    }

    void clerkDied(String village) {
        store.clerkDeaths.put(village, System.currentTimeMillis());
        store.saveVillages();
    }

    /** Every few minutes: Postmasters back on their spot, and replaced once the respawn time is up. */
    void startChecks() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Office o : store.offices.values()) {
                World w = Bukkit.getWorld(o.world());
                if (w != null && w.isChunkLoaded(((int) Math.floor(o.x())) >> 4, ((int) Math.floor(o.z())) >> 4)) checkClerk(o);
            }
        }, 1200L, 1200L);
    }

    private void checkClerk(Office o) {
        World w = Bukkit.getWorld(o.world());
        if (w == null) return;
        List<Villager> clerks = new ArrayList<>();
        for (Entity en : w.getNearbyEntities(new Location(w, o.x(), o.y(), o.z()), 8, 6, 8)) {
            if (en instanceof Villager v && v.isValid()
                    && o.village().equals(v.getPersistentDataContainer().get(Keys.CLERK, PersistentDataType.STRING))) {
                clerks.add(v);
            }
        }
        if (clerks.isEmpty()) {
            // After he's killed, a new Postmaster takes over once the respawn time has passed.
            Long died = store.clerkDeaths.get(o.village());
            long wait = (long) (plugin.getConfig().getDouble("post-offices.postmaster-respawn-minutes", 60) * 60_000);
            if (died != null && System.currentTimeMillis() - died < wait) return;
            if (died != null) {
                store.clerkDeaths.remove(o.village());
                store.saveVillages();
            }
            spawnClerk(o);
            return;
        }
        // Postmasters from older versions were invulnerable and pushable; keep him on his spot.
        Villager clerk = clerks.getFirst();
        clerk.setInvulnerable(false);
        clerk.setCollidable(false);
        Location spot = new Location(w, o.x(), o.y(), o.z(), o.yaw(), 0f);
        if (clerk.getLocation().distanceSquared(spot) > 0.25) {
            clerk.teleport(spot, io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
        }
        for (int i = 1; i < clerks.size(); i++) {
            for (Entity passenger : clerks.get(i).getPassengers()) passenger.remove();
            clerks.get(i).remove();
        }
    }
}

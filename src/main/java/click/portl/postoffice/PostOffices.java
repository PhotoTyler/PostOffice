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
 * Builds a small Post Office in every village: a public drop-off postbox, a counter and a Postmaster
 * villager who trades. Each village is handled once, the first time one of its chunks loads (so
 * villages that already exist get one too). Spots are picked in a gap between the village's own
 * buildings and streets, on fairly flat ground, with the door facing the nearest street.
 */
final class PostOffices implements Listener {
    /** Owner of drop-offs the plugin builds: only admins can remove them. */
    static final UUID SERVER = new UUID(0L, 0L);

    record Office(String village, String world, double x, double y, double z, float yaw) {}

    /** Building materials per village style. */
    private record Palette(Material pillar, Material wall, Material counter, Material stairs, Material roof,
                           Material slab, Material door, Material sign, Material foundation, Material floor,
                           Material path) {}

    private static final Palette PLAINS = new Palette(Material.OAK_LOG, Material.OAK_PLANKS, Material.STRIPPED_OAK_LOG,
            Material.OAK_STAIRS, Material.OAK_PLANKS, Material.OAK_SLAB, Material.OAK_DOOR, Material.OAK_WALL_SIGN,
            Material.COBBLESTONE, Material.SPRUCE_PLANKS, Material.DIRT_PATH);
    private static final Palette DESERT = new Palette(Material.CUT_SANDSTONE, Material.SMOOTH_SANDSTONE,
            Material.CUT_SANDSTONE, Material.SMOOTH_SANDSTONE_STAIRS, Material.SMOOTH_SANDSTONE,
            Material.SMOOTH_SANDSTONE_SLAB, Material.JUNGLE_DOOR, Material.JUNGLE_WALL_SIGN, Material.SANDSTONE,
            Material.CUT_SANDSTONE, Material.SMOOTH_SANDSTONE);
    private static final Palette SAVANNA = new Palette(Material.ACACIA_LOG, Material.ACACIA_PLANKS,
            Material.STRIPPED_ACACIA_LOG, Material.ACACIA_STAIRS, Material.ACACIA_PLANKS, Material.ACACIA_SLAB,
            Material.ACACIA_DOOR, Material.ACACIA_WALL_SIGN, Material.COBBLESTONE, Material.OAK_PLANKS, Material.DIRT_PATH);
    private static final Palette SPRUCE = new Palette(Material.SPRUCE_LOG, Material.SPRUCE_PLANKS,
            Material.STRIPPED_SPRUCE_LOG, Material.SPRUCE_STAIRS, Material.SPRUCE_PLANKS, Material.SPRUCE_SLAB,
            Material.SPRUCE_DOOR, Material.SPRUCE_WALL_SIGN, Material.COBBLESTONE, Material.SPRUCE_PLANKS, Material.DIRT_PATH);

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

    private void consider(World w, GeneratedStructure gs) {
        NamespacedKey key = RegistryAccess.registryAccess().getRegistry(RegistryKey.STRUCTURE).getKey(gs.getStructure());
        if (key == null || !key.getKey().startsWith("village")) return;
        BoundingBox box = gs.getBoundingBox();
        String id = w.getName() + "|" + key.getKey() + "|" + (int) box.getMinX() + "," + (int) box.getMinY() + "," + (int) box.getMinZ();
        if (store.villagesDone.contains(id) || !busy.add(id)) return;
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
                    if (store.villagesDone.contains(id)) return;
                    store.villagesDone.add(id);
                    if (err != null) {
                        plugin.getLogger().warning("Couldn't load village " + id + ": " + err);
                    } else {
                        Site site = findSite(w, cx, cz, r, pieces);
                        if (site == null) plugin.getLogger().info("No room for a Post Office in village " + id);
                        else build(w, id, site, palette);
                    }
                    store.saveVillages();
                }));
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
            int dx = lx - 4, dz = lz - 4;
            int rx, rz;
            switch (s.door) {
                case NORTH -> { rx = -dx; rz = -dz; }
                case EAST -> { rx = dz; rz = -dx; }
                case WEST -> { rx = -dz; rz = dx; }
                default -> { rx = dx; rz = dz; }
            }
            return w.getBlockAt(s.x0 + 4 + rx, s.ground + y, s.z0 + 4 + rz);
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
                    b.set(c[0], y, c[1], corner ? p.pillar : p.wall);
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

        // Windows
        for (int[] win : new int[][]{{1, 2, 4}, {1, 3, 4}, {7, 2, 4}, {7, 3, 4}, {2, 2, 7}, {6, 2, 7}, {4, 3, 1}}) {
            b.at(win[0], win[1], win[2]).setType(Material.GLASS_PANE, true);
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
        Slab top = (Slab) p.slab.createBlockData();
        top.setType(Slab.Type.BOTTOM);
        b.set(4, 9, 4, top, false);

        // Counter either side of the drop-off, a bookshelf, a lamp and a red carpet to the door
        for (int lx : new int[]{2, 3, 5, 6}) b.set(lx, 1, 3, p.counter);
        b.set(6, 1, 2, Material.BOOKSHELF);
        b.set(6, 2, 2, Material.BOOKSHELF);
        b.set(2, 2, 3, Material.POTTED_POPPY);
        for (int lz = 4; lz <= 6; lz++) b.set(4, 1, lz, Material.RED_CARPET);
        Lantern lamp = (Lantern) Material.LANTERN.createBlockData();
        lamp.setHanging(true);
        b.set(4, 4, 5, lamp, false);
        b.set(2, 4, 2, lamp.clone(), false);

        // "POST OFFICE" sign over the door, outside
        BlockData signData = p.sign.createBlockData();
        ((Directional) signData).setFacing(b.face(BlockFace.SOUTH));
        Block signBlock = b.at(4, 3, 8);
        signBlock.setBlockData(signData, false);
        if (signBlock.getState() instanceof Sign sign) {
            List<String> lines = plugin.getConfig().getStringList("post-offices.sign");
            for (int i = 0; i < 4 && i < lines.size(); i++) sign.getSide(Side.FRONT).line(i, Msg.parse(lines.get(i)));
            sign.setWaxed(true);
            sign.update(true, false);
        }

        // The public drop-off, facing the door
        float yaw = yawOf(b.face(BlockFace.SOUTH));
        Block dropBlock = b.at(4, 1, 2);
        PostBlock drop = plugin.blocks().placeSystem(PostBlock.Type.DROPOFF, dropBlock, yaw, SERVER, "Post Office");

        // The Postmaster behind the counter
        Block clerkBlock = b.at(2, 1, 2);
        Office office = new Office(id, w.getName(), clerkBlock.getX() + 0.5, clerkBlock.getY(), clerkBlock.getZ() + 0.5, yaw);
        store.offices.put(id, office);
        spawnClerk(office);

        plugin.getLogger().info("Built a Post Office in village " + id + " at " + dropBlock.getX() + ", "
                + dropBlock.getY() + ", " + dropBlock.getZ() + (drop == null ? " (drop-off failed)" : ""));
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
            villager.setInvulnerable(true);
            villager.getPersistentDataContainer().set(Keys.CLERK, PersistentDataType.STRING, o.village());
            villager.getPersistentDataContainer().set(Keys.TRADER, PersistentDataType.BOOLEAN, true);
        });
        Postman.addCap(plugin, v, Keys.CLERK, o.village(), true);
        plugin.traders().track(v);
    }

    /** Puts the Postmaster back if he has gone missing. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        Chunk c = e.getChunk();
        List<Office> here = new ArrayList<>();
        for (Office o : store.offices.values()) {
            if (o.world().equals(c.getWorld().getName()) && ((int) Math.floor(o.x())) >> 4 == c.getX()
                    && ((int) Math.floor(o.z())) >> 4 == c.getZ()) here.add(o);
        }
        if (here.isEmpty()) return;
        Set<String> present = new HashSet<>();
        for (Entity en : e.getEntities()) {
            String id = en.getPersistentDataContainer().get(Keys.CLERK, PersistentDataType.STRING);
            if (id != null && en instanceof Villager) present.add(id);
        }
        for (Office o : here) {
            if (!present.contains(o.village())) Bukkit.getScheduler().runTask(plugin, () -> {
                if (c.isLoaded()) spawnClerk(o);
            });
        }
    }
}

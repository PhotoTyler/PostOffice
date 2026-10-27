package click.portl.postoffice;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/** Finds somewhere a villager can stand. */
final class Spots {
    private Spots() {}

    static boolean standable(Block feet) {
        Block below = feet.getRelative(0, -1, 0);
        return feet.isPassable() && !feet.isLiquid()
                && feet.getRelative(0, 1, 0).isPassable() && !feet.getRelative(0, 1, 0).isLiquid()
                && below.getType().isSolid();
    }

    /** Looks near {@code around} (columns within 2 blocks, heights near refY), else the surface. */
    static Location findSafe(Location around, double refY) {
        if (around == null || around.getWorld() == null) return null;
        World w = around.getWorld();
        int bx = around.getBlockX(), bz = around.getBlockZ();
        int ry = (int) Math.floor(refY);
        int[] offsets = {0, 1, -1, 2, -2};
        for (int r = 0; r <= 2; r++) {
            for (int ox : offsets) for (int oz : offsets) {
                if (Math.max(Math.abs(ox), Math.abs(oz)) != r) continue;
                for (int i = 0; i <= 16; i++) {
                    int dy = (i % 2 == 0) ? i / 2 : -(i + 1) / 2;
                    int y = ry + dy;
                    if (y <= w.getMinHeight() || y >= w.getMaxHeight() - 1) continue;
                    Block feet = w.getBlockAt(bx + ox, y, bz + oz);
                    if (standable(feet)) return new Location(w, feet.getX() + 0.5, y, feet.getZ() + 0.5);
                }
            }
        }
        if (w.getEnvironment() == World.Environment.NORMAL || w.getEnvironment() == World.Environment.THE_END) {
            int top = w.getHighestBlockYAt(bx, bz);
            Block feet = w.getBlockAt(bx, top + 1, bz);
            if (standable(feet)) return new Location(w, bx + 0.5, top + 1, bz + 0.5);
        }
        return null;
    }
}

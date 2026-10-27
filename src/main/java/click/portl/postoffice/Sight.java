package click.portl.postoffice;

import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/** Can any player see a spot? Used so the Postman appears and disappears out of view. */
final class Sight {
    private static final double RANGE = 96;
    private static final double HALF_FOV_COS = Math.cos(Math.toRadians(65));

    private Sight() {}

    /** True if a player is looking roughly towards {@code feet} with nothing in the way. */
    static boolean seen(Location feet) {
        World w = feet.getWorld();
        for (Player p : w.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            Location eye = p.getEyeLocation();
            if (eye.distanceSquared(feet) > RANGE * RANGE) continue;
            for (double up : new double[]{1.6, 0.3}) { // head and feet
                Vector to = feet.clone().add(0, up, 0).toVector().subtract(eye.toVector());
                double dist = to.length();
                if (dist < 2) return true;
                Vector dir = to.clone().multiply(1 / dist);
                if (dir.dot(eye.getDirection()) < HALF_FOV_COS) continue; // behind or to the side
                if (w.rayTraceBlocks(eye, dir, dist - 0.5, FluidCollisionMode.NEVER, true) == null) return true;
            }
        }
        return false;
    }

    /**
     * A standable spot {@code minR}..{@code maxR} blocks from {@code center} that no player can see,
     * only in loaded chunks. Falls back to any standable spot there, then null.
     */
    static Location hiddenSpotNear(Location center, double minR, double maxR) {
        World w = center.getWorld();
        Location fallback = null;
        double start = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        for (double r : new double[]{minR, (minR + maxR) / 2, maxR}) {
            for (int i = 0; i < 12; i++) {
                double a = start + i * Math.PI / 6;
                Location c = center.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r);
                if (!w.isChunkLoaded(c.getBlockX() >> 4, c.getBlockZ() >> 4)) continue;
                Location spot = Spots.findSafe(c, center.getY());
                if (spot == null) continue;
                if (!seen(spot)) return spot;
                if (fallback == null) fallback = spot;
            }
        }
        return fallback;
    }
}

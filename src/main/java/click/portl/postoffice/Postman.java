package click.portl.postoffice;

import com.destroystokyo.paper.entity.Pathfinder;
import io.papermc.paper.entity.TeleportFlag;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * One Postman villager and the mail in his bag.
 *
 * The villager's own AI is switched off (no wandering, sleeping, panicking or trading); instead he
 * follows paths from Paper's pathfinder, moved a little every tick. If he gets stuck he hops ahead,
 * and if a mailbox is far away (or in another world) he "takes the post road": vanishes and turns up
 * near the mailbox after a short delay. He keeps the chunks around him loaded while he works.
 */
final class Postman {
    private enum State { STARTING, WALKING, DROPPING, TRAVELLING, ARRIVING, LEAVING, DONE }

    final UUID id = UUID.randomUUID();
    private final PostOfficePlugin plugin;
    private final PostService service;
    private final List<Delivery> bag;

    private Villager villager;
    private ItemDisplay hat;
    private State state = State.STARTING;
    private PostBlock target;
    private List<Location> path;
    private int pathIndex;
    private int repathIn;
    private Location lastProgress;
    private int stuck;
    private int wait;
    private int age;
    private Location lastSeen;
    private final Set<Long> tickets = new HashSet<>();
    private World ticketWorld;

    Postman(PostOfficePlugin plugin, PostService service, List<Delivery> bag) {
        this.plugin = plugin;
        this.service = service;
        this.bag = new ArrayList<>(bag);
    }

    List<Delivery> bag() {
        return bag;
    }

    private double cfg(String key, double def) {
        return plugin.getConfig().getDouble("postman." + key, def);
    }

    // ---------------- lifecycle ----------------

    void start(Location dropoff) {
        Block b = dropoff.getBlock();
        PostBlock pb = plugin.blocks().at(b);
        Location spot = pb != null ? plugin.blocks().front(pb) : dropoff;
        Location safe = Spots.findSafe(spot, spot.getY());
        if (safe == null) safe = Spots.findSafe(dropoff.clone().add(1, 0, 0), dropoff.getY());
        if (safe == null) safe = dropoff.clone().add(0, 1, 0);
        spawn(safe);
        if (pb != null) face(pb.center());
        poof(villager.getLocation());
        nextTarget();
    }

    private void spawn(Location at) {
        World w = at.getWorld();
        w.getChunkAt(at); // make sure it's loaded
        villager = w.spawn(at, Villager.class, v -> {
            dress(plugin, v, plugin.getConfig().getString("postman.display-name", "<gold>Postman"), false);
            v.setGravity(false);
            v.getPersistentDataContainer().set(Keys.POSTMAN, PersistentDataType.STRING, id.toString());
        });
        hat = addCap(plugin, villager, Keys.POSTMAN, id.toString(), false);
        lastProgress = at.clone();
        stuck = 0;
        path = null;
        state = State.WALKING;
        syncTickets();
    }

    /** Postman look and settings, shared with the post office clerk. AI off: we move him ourselves. */
    static void dress(PostOfficePlugin plugin, Villager v, String name, boolean persistent) {
        v.setPersistent(persistent);
        v.setRemoveWhenFarAway(false);
        v.customName(Msg.item(name));
        v.setCustomNameVisible(true);
        v.setAdult();
        v.setAgeLock(true);
        v.setCanPickupItems(false);
        v.setAware(false);
        v.setRecipes(List.of());
        v.setVillagerType(Villager.Type.PLAINS);
        // Nitwit by default: the resource pack turns the nitwit outfit into the postal uniform.
        Villager.Profession prof = RegistryAccess.registryAccess().getRegistry(RegistryKey.VILLAGER_PROFESSION)
                .get(Key.key(plugin.getConfig().getString("postman.profession", "nitwit").toLowerCase()));
        if (prof != null) v.setProfession(prof);
    }

    /** The 3D postman cap from the resource pack, riding on his head. */
    static ItemDisplay addCap(PostOfficePlugin plugin, Villager v, org.bukkit.NamespacedKey tag, String id, boolean persistent) {
        if (!plugin.getConfig().getBoolean("postman.hat", true)) return null;
        ItemDisplay cap = v.getWorld().spawn(v.getLocation(), ItemDisplay.class, d -> {
            d.setItemStack(Displays.modelItem("postman_cap"));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setPersistent(persistent);
            d.setTeleportDuration(2);
            d.setTransformation(new Transformation(new Vector3f(0f, (float) plugin.getConfig().getDouble("postman.hat-offset", 0.02), 0f),
                    new Quaternionf(), new Vector3f(1f, 1f, 1f), new Quaternionf()));
            d.getPersistentDataContainer().set(tag, PersistentDataType.STRING, id);
        });
        v.addPassenger(cap);
        return cap;
    }

    void despawnQuietly() {
        if (hat != null) hat.remove();
        if (villager != null) villager.remove();
        hat = null;
        villager = null;
        releaseTickets();
        state = State.DONE;
    }

    private void finish() {
        if (villager != null) poof(villager.getLocation());
        despawnQuietly();
        service.finished(this);
    }

    /** Called from the death listener. */
    void killed() {
        if (hat != null) hat.remove();
        hat = null;
        villager = null;
        releaseTickets();
        state = State.DONE;
    }

    // ---------------- tick ----------------

    void tick() {
        if (state == State.DONE) return;
        age++;
        if (state == State.TRAVELLING) {
            if (--wait <= 0) arrive();
            return;
        }
        if (state == State.ARRIVING || state == State.STARTING) return;

        if (villager == null || !villager.isValid()) {
            // Removed by something other than a player kill: the mail still gets through.
            plugin.getLogger().info("A Postman vanished; delivering his " + bag.size() + " item(s) directly.");
            for (Delivery d : new ArrayList<>(bag)) service.deliver(d);
            bag.clear();
            despawnQuietly();
            service.finished(this);
            return;
        }
        lastSeen = villager.getLocation();
        if (age > cfg("max-lifetime-seconds", 600) * 20 && state != State.LEAVING) {
            for (Delivery d : new ArrayList<>(bag)) service.deliver(d);
            bag.clear();
            leave();
            return;
        }
        if (hat != null && hat.isValid()) hat.setRotation(villager.getYaw(), 0f);
        if (age % 10 == 0) syncTickets();

        switch (state) {
            case DROPPING -> {
                if (--wait <= 0) nextTarget();
            }
            case LEAVING -> {
                if (--wait <= 0) finish();
            }
            case WALKING -> walk();
            default -> {}
        }
    }

    private void nextTarget() {
        target = null;
        double best = Double.MAX_VALUE;
        Location here = villager != null ? villager.getLocation() : lastSeen;
        for (Delivery d : new ArrayList<>(bag)) {
            PostBlock box = service.resolveTarget(d);
            if (box == null) {
                bag.remove(d);
                service.deliver(d); // goes back to the sender
                continue;
            }
            double dist = here != null && box.world.equals(here.getWorld().getName())
                    ? box.center().distanceSquared(here) : Double.MAX_VALUE / 2;
            if (dist < best) {
                best = dist;
                target = box;
            }
        }
        if (target == null) {
            leave();
            return;
        }
        path = null;
        stuck = 0;
        double walk = cfg("walk-distance", 96);
        if (here == null || !target.world.equals(here.getWorld().getName()) || best > walk * walk) {
            travel();
        } else {
            state = State.WALKING;
        }
    }

    private void leave() {
        state = State.LEAVING;
        wait = 30;
        if (villager != null) villager.getWorld().playSound(villager.getLocation(), Sound.ENTITY_VILLAGER_CELEBRATE, 1f, 1f);
    }

    // ---------------- walking ----------------

    private boolean arrived(Location here) {
        Location c = target.center();
        double dx = here.getX() - c.getX(), dz = here.getZ() - c.getZ();
        return dx * dx + dz * dz <= 2.4 * 2.4 && Math.abs(here.getY() - target.y) <= 2.5;
    }

    private void walk() {
        if (!target.isLoaded() && villager.getLocation().distanceSquared(target.center()) < 64 * 64) {
            target.bukkitWorld().getChunkAt(target.x >> 4, target.z >> 4);
        }
        Location here = villager.getLocation();
        if (arrived(here)) {
            dropOff();
            return;
        }
        if (path == null || pathIndex >= path.size() || --repathIn <= 0) {
            repath(here);
            if (path == null) {
                hop(here);
                return;
            }
        }
        step(here);

        if (age % 60 == 0) {
            if (lastProgress != null && lastProgress.getWorld() == here.getWorld() && lastProgress.distanceSquared(here) < 1.0) {
                if (++stuck >= 2) hop(villager.getLocation());
            } else {
                stuck = 0;
            }
            lastProgress = villager.getLocation();
        }
    }

    private void repath(Location here) {
        Location goal = target.center();
        double dist = here.distance(goal);
        Location dest;
        if (dist <= 24) {
            dest = plugin.blocks().front(target);
        } else {
            Vector dir = goal.toVector().subtract(here.toVector()).setY(0).normalize().multiply(20);
            Location ahead = here.clone().add(dir);
            ahead.getWorld().getChunkAt(ahead.getBlockX() >> 4, ahead.getBlockZ() >> 4);
            double refY = here.getY() + (goal.getY() - here.getY()) * (20 / dist);
            dest = Spots.findSafe(ahead, refY);
            if (dest == null) {
                path = null;
                return;
            }
        }
        Pathfinder.PathResult result = villager.getPathfinder().findPath(dest);
        if (result == null || result.getPoints().size() < 2) {
            path = null;
            return;
        }
        path = new ArrayList<>(result.getPoints());
        pathIndex = 1;
        repathIn = 100;
    }

    private void step(Location here) {
        double speed = cfg("speed", 0.17);
        Location node = path.get(pathIndex);
        openDoor(node.getBlock());
        double tx = node.getBlockX() + 0.5, ty = node.getBlockY(), tz = node.getBlockZ() + 0.5;
        double dx = tx - here.getX(), dy = ty - here.getY(), dz = tz - here.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        Location next;
        if (dist <= speed) {
            next = new Location(here.getWorld(), tx, ty, tz);
            pathIndex++;
        } else {
            double f = speed / dist;
            next = new Location(here.getWorld(), here.getX() + dx * f, here.getY() + dy * f, here.getZ() + dz * f);
        }
        float yaw = (Math.abs(dx) + Math.abs(dz) > 0.01) ? (float) Math.toDegrees(Math.atan2(-dx, dz)) : here.getYaw();
        next.setYaw(yaw);
        next.setPitch(0);
        move(next);
    }

    /** Villager paths go through doors; open them on the way and shut them behind him. */
    private void openDoor(Block b) {
        if (!org.bukkit.Tag.WOODEN_DOORS.isTagged(b.getType())) return;
        if (!(b.getBlockData() instanceof org.bukkit.block.data.type.Door door)) return;
        if (door.getHalf() == org.bukkit.block.data.Bisected.Half.TOP) {
            b = b.getRelative(0, -1, 0);
            if (!(b.getBlockData() instanceof org.bukkit.block.data.type.Door lower)) return;
            door = lower;
        }
        if (door.isOpen()) return;
        door.setOpen(true);
        b.setBlockData(door, true);
        b.getWorld().playSound(b.getLocation(), Sound.BLOCK_WOODEN_DOOR_OPEN, 1f, 1f);
        Block lower = b;
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (lower.getBlockData() instanceof org.bukkit.block.data.type.Door d && d.isOpen()) {
                d.setOpen(false);
                lower.setBlockData(d, true);
                lower.getWorld().playSound(lower.getLocation(), Sound.BLOCK_WOODEN_DOOR_CLOSE, 1f, 1f);
            }
        }, 40L);
    }

    private void move(Location to) {
        villager.teleport(to, TeleportFlag.EntityState.RETAIN_PASSENGERS);
        villager.setBodyYaw(to.getYaw());
    }

    /** Stuck or no path: jump a bit closer with a puff of smoke. */
    private void hop(Location here) {
        Location goal = target.center();
        Location to;
        if (here.distanceSquared(goal) <= 14 * 14) {
            to = Spots.findSafe(plugin.blocks().front(target), target.y);
        } else {
            Vector dir = goal.toVector().subtract(here.toVector()).setY(0).normalize().multiply(12);
            Location ahead = here.clone().add(dir);
            double refY = here.getY() + (goal.getY() - here.getY()) * (12 / here.distance(goal));
            to = Spots.findSafe(ahead, refY);
        }
        if (to == null) to = Spots.findSafe(goal.clone().add(1, -0.5, 0), target.y);
        if (to == null) {
            dropOff(); // nowhere to stand at all: post it anyway
            return;
        }
        poof(here);
        to.setYaw(here.getYaw());
        move(to);
        poof(to);
        path = null;
        stuck = 0;
        lastProgress = to.clone();
    }

    // ---------------- long trips ----------------

    private void travel() {
        Location from = villager != null ? villager.getLocation() : lastSeen;
        if (villager != null) {
            poof(from);
            despawnEntityOnly();
        }
        double dist = from != null && from.getWorld().getName().equals(target.world) ? from.distance(target.center()) : 500;
        double secs = Math.max(cfg("min-travel-seconds", 3), Math.min(cfg("max-travel-seconds", 45),
                dist / cfg("travel-blocks-per-second", 25)));
        wait = (int) (secs * 20);
        state = State.TRAVELLING;
    }

    private void despawnEntityOnly() {
        if (hat != null) hat.remove();
        if (villager != null) villager.remove();
        hat = null;
        villager = null;
        releaseTickets();
    }

    private void arrive() {
        PostBlock box = target;
        if (box == null || box.bukkitWorld() == null) {
            nextTarget();
            return;
        }
        state = State.ARRIVING;
        Location goal = box.center();
        Vector dir = lastSeen != null && lastSeen.getWorld() == goal.getWorld()
                ? lastSeen.toVector().subtract(goal.toVector()).setY(0)
                : new Vector(Math.random() - 0.5, 0, Math.random() - 0.5);
        if (dir.lengthSquared() < 0.01) dir = new Vector(1, 0, 0);
        Location near = goal.clone().add(dir.normalize().multiply(cfg("arrive-distance", 10)));
        goal.getWorld().getChunkAtAsync(near).thenAccept(chunk -> {
            if (state != State.ARRIVING) return;
            box.bukkitWorld().getChunkAt(box.x >> 4, box.z >> 4);
            Location spot = Spots.findSafe(near, box.y);
            if (spot == null) spot = Spots.findSafe(plugin.blocks().front(box), box.y);
            if (spot == null) spot = box.center().add(0, 1, 0);
            spawn(spot);
            poof(spot);
            face(box.center());
        });
    }

    // ---------------- delivering ----------------

    private void dropOff() {
        face(target.center());
        Location c = target.center();
        c.getWorld().playSound(c, Sound.ITEM_BUNDLE_DROP_CONTENTS, 1f, 1f);
        c.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, c.clone().add(0, 0.6, 0), 8, 0.3, 0.3, 0.3);
        for (Delivery d : new ArrayList<>(bag)) {
            if (target.id.equals(d.mailbox)) {
                bag.remove(d);
                service.deliver(d);
            }
        }
        state = State.DROPPING;
        wait = 30;
    }

    private void face(Location at) {
        if (villager == null) return;
        Location here = villager.getLocation();
        double dx = at.getX() - here.getX(), dz = at.getZ() - here.getZ();
        here.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        move(here);
    }

    private static void poof(Location at) {
        at.getWorld().spawnParticle(Particle.CLOUD, at.clone().add(0, 1, 0), 15, 0.3, 0.5, 0.3, 0.02);
        at.getWorld().playSound(at, Sound.ENTITY_VILLAGER_AMBIENT, 0.8f, 1.2f);
    }

    // ---------------- chunk tickets ----------------

    private void syncTickets() {
        if (villager == null) return;
        World w = villager.getWorld();
        if (ticketWorld != null && ticketWorld != w) releaseTickets();
        ticketWorld = w;
        Chunk c = villager.getLocation().getChunk();
        Set<Long> want = new HashSet<>();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) want.add(Chunk.getChunkKey(c.getX() + dx, c.getZ() + dz));
        for (Long k : want) if (tickets.add(k)) w.addPluginChunkTicket((int) (long) k, (int) (k >> 32), plugin);
        tickets.removeIf(k -> {
            if (want.contains(k)) return false;
            w.removePluginChunkTicket((int) (long) k, (int) (k >> 32), plugin);
            return true;
        });
    }

    private void releaseTickets() {
        if (ticketWorld != null) for (Long k : tickets) ticketWorld.removePluginChunkTicket((int) (long) k, (int) (k >> 32), plugin);
        tickets.clear();
    }
}

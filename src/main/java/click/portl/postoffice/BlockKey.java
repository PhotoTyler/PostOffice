package click.portl.postoffice;

import org.bukkit.block.Block;

record BlockKey(String world, int x, int y, int z) {
    static BlockKey of(Block b) {
        return new BlockKey(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }
}

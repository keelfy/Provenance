package dev.keelfy.provenance.core.model;

/**
 * A block in a world. The text form {@code world:x:y:z} is the id of a block holder; the world name may contain colons
 * itself.
 *
 * @author keelfy
 */
public record BlockPos(String world, int x, int y, int z) {

    public String key() {
        return world + ":" + x + ":" + y + ":" + z;
    }

    public static BlockPos parse(String key) {
        var z = key.lastIndexOf(':');
        var y = key.lastIndexOf(':', z - 1);
        var x = key.lastIndexOf(':', y - 1);
        return new BlockPos(
                key.substring(0, x),
                Integer.parseInt(key.substring(x + 1, y)),
                Integer.parseInt(key.substring(y + 1, z)),
                Integer.parseInt(key.substring(z + 1)));
    }

    public Chunk chunk() {
        return Chunk.ofBlock(world, x, z);
    }
}

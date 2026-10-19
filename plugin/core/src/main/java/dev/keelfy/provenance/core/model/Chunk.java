package dev.keelfy.provenance.core.model;

/**
 * A 16 x 16 column of a world: the grain at which the journal tells where things happened.
 *
 * @author keelfy
 */
public record Chunk(String world, int x, int z) {

    private static final int SHIFT = 4;

    public static Chunk ofBlock(String world, int blockX, int blockZ) {
        return new Chunk(world, blockX >> SHIFT, blockZ >> SHIFT);
    }

    /** {@code world:x:z}; the world name may contain colons itself. */
    public String key() {
        return world + ":" + x + ":" + z;
    }

    public static Chunk parse(String key) {
        var end = key.lastIndexOf(':');
        var start = key.lastIndexOf(':', end - 1);
        return new Chunk(
                key.substring(0, start),
                Integer.parseInt(key.substring(start + 1, end)),
                Integer.parseInt(key.substring(end + 1)));
    }
}

package dev.keelfy.provenance.app.world;

/**
 * A point in a world, named by the world's name.
 *
 * @author keelfy
 */
public record WorldPos(String world, double x, double y, double z) {}

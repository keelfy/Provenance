package dev.keelfy.provenance.core.model;

import java.util.Objects;

/**
 * Fungibility class of an item: its type, e.g. {@code minecraft:diamond}, followed by {@code #<hash>} when its data
 * components set it apart, e.g. {@code minecraft:diamond_sword#3f9a…} for an enchanted sword.
 *
 * @author keelfy
 */
public record ItemKey(String key) {

    public ItemKey {
        Objects.requireNonNull(key, "key");
    }
}

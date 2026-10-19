package dev.keelfy.provenance.core.model;

import java.util.Objects;

/**
 * Fungibility class of an item, e.g. {@code minecraft:diamond}.
 *
 * @author keelfy
 */
public record ItemKey(String key) {

    public ItemKey {
        Objects.requireNonNull(key, "key");
    }
}

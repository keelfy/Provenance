package dev.keelfy.provenance.core;

import java.util.Objects;

import org.jetbrains.annotations.NotNull;

/** 
 * Fungibility class of an item, e.g. {@code minecraft:diamond}.
 * 
 * @author keelfy
 * */
public record ItemKey(@NotNull String key) {

    public ItemKey {
        Objects.requireNonNull(key, "key");
    }
}

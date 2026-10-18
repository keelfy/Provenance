package dev.keelfy.provenance.core;

import java.util.Objects;
import java.util.UUID;

/**
 * Source an item share is attributed to: a player or an environment bucket.
 *
 * @author keelfy
 */
public record OriginId(String key) {

    private static final String PLAYER_PREFIX = "p:";

    public static final OriginId MOB = new OriginId("env:mob");
    public static final OriginId LOOT = new OriginId("env:loot");
    public static final OriginId TRADE = new OriginId("env:trade");
    public static final OriginId CREATIVE = new OriginId("env:creative");
    /** Items that predate tracking or appeared through an unmodelled path. */
    public static final OriginId UNKNOWN = new OriginId("env:unknown");
    /** Aggregated tail removed by top-K pruning. */
    public static final OriginId OTHER = new OriginId("env:other");

    public OriginId {
        Objects.requireNonNull(key, "key");
    }

    public static OriginId player(UUID uuid) {
        return new OriginId(PLAYER_PREFIX + uuid);
    }

    public boolean isPlayer() {
        return key.startsWith(PLAYER_PREFIX);
    }

    public UUID playerId() {
        if (!isPlayer()) {
            throw new IllegalStateException("not a player origin: " + key);
        }
        return UUID.fromString(key.substring(PLAYER_PREFIX.length()));
    }
}

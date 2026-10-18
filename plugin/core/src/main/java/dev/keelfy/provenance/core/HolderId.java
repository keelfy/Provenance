package dev.keelfy.provenance.core;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Anything that holds items: a player inventory, a container block, or an entity. {@code info} says what a block or an
 * entity was when the event happened; it travels with events to the journal but is not part of the identity:
 * {@link #equals} and {@link #hashCode} look at {@code kind} and {@code id} only.
 *
 * @author keelfy
 */
public record HolderId(Kind kind, String id, @Nullable HolderInfo info) {

    public HolderId {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
    }

    public HolderId(Kind kind, String id) {
        this(kind, id, null);
    }

    public HolderId described(HolderInfo info) {
        return new HolderId(kind, id, info);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof HolderId that && kind == that.kind && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return 31 * kind.hashCode() + id.hashCode();
    }

    public static HolderId player(UUID uuid) {
        return new HolderId(Kind.PLAYER, uuid.toString());
    }

    public static HolderId block(String world, int x, int y, int z) {
        return new HolderId(Kind.BLOCK, world + ":" + x + ":" + y + ":" + z);
    }

    public static HolderId entity(UUID uuid) {
        return new HolderId(Kind.ENTITY, uuid.toString());
    }

    public boolean isPlayer() {
        return kind == Kind.PLAYER;
    }

    /** The origin this player holder stands for as a custodian. */
    public OriginId asPlayerOrigin() {
        if (!isPlayer()) {
            throw new IllegalStateException("not a player holder: " + this);
        }
        return OriginId.player(UUID.fromString(id));
    }
}

package dev.keelfy.provenance.core;

import java.util.Objects;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;

/** 
 * Anything that holds items: a player inventory, a container block, or an entity. 
 * 
 * @author keelfy
 * */
public record HolderId(@NotNull Kind kind, @NotNull String id) {

    public HolderId {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
    }

    public static @NotNull HolderId player(@NotNull UUID uuid) {
        return new HolderId(Kind.PLAYER, uuid.toString());
    }

    public static @NotNull HolderId block(@NotNull String world, int x, int y, int z) {
        return new HolderId(Kind.BLOCK, world + ":" + x + ":" + y + ":" + z);
    }

    public static @NotNull HolderId entity(@NotNull UUID uuid) {
        return new HolderId(Kind.ENTITY, uuid.toString());
    }

    public boolean isPlayer() {
        return kind == Kind.PLAYER;
    }

    /** The origin this player holder stands for as a custodian. */
    public @NotNull OriginId asPlayerOrigin() {
        if (!isPlayer()) {
            throw new IllegalStateException("not a player holder: " + this);
        }
        return OriginId.player(UUID.fromString(id));
    }
}

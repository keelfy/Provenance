package dev.keelfy.provenance.core.model;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Anything that holds items: a player inventory, a container block, or an entity. {@code meta} says what a block or an
 * entity was when the event happened; it is not part of the identity: {@link #equals} and {@link #hashCode} look at
 * {@code id} only.
 *
 * @author keelfy
 */
public record Holder(HolderId id, HolderMeta meta) {

    public Holder {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(meta, "meta");
    }

    public Holder(HolderId id) {
        this(id, HolderMeta.NONE);
    }

    public Holder(Kind kind, String id) {
        this(new HolderId(kind, id), HolderMeta.NONE);
    }

    public Holder(Kind kind, String id, HolderMeta meta) {
        this(new HolderId(kind, id), meta);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Holder that && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    public Holder described(@Nullable String type, @Nullable String label, @Nullable Chunk chunk) {
        return new Holder(id, new HolderMeta(type, label, chunk));
    }

    public Holder described(@Nullable String type, @Nullable String label) {
        return new Holder(id, new HolderMeta(type, label, meta.chunk()));
    }

    public Holder described(@Nullable String type) {
        return type != null ? new Holder(id, new HolderMeta(type, meta.label(), meta.chunk())) : this;
    }

    public static Holder player(UUID uuid) {
        return new Holder(Kind.PLAYER, uuid.toString());
    }

    public static Holder block(String world, int x, int y, int z) {
        return new Holder(Kind.BLOCK, new BlockPos(world, x, y, z).key());
    }

    public static Holder entity(UUID uuid) {
        return new Holder(Kind.ENTITY, uuid.toString());
    }

    public boolean isPlayer() {
        return id.isPlayer();
    }

    /** The origin this player holder stands for as a custodian. */
    public OriginId asPlayerOrigin() {
        return id.asPlayerOrigin();
    }

    public @Nullable String type() {
        return meta.type();
    }

    public @Nullable String label() {
        return meta.label();
    }

    /** Where it is: a block's chunk comes from its coordinates, an entity's from the meta recorded with the event. */
    public @Nullable Chunk chunk() {
        return switch (id.kind()) {
            case BLOCK -> id.blockPos().chunk();
            case ENTITY -> meta.chunk();
            default -> null;
        };
    }

    public String displayName() {
        return meta.displayName(id.kind());
    }
}

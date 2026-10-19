package dev.keelfy.provenance.core.model;

import java.util.Objects;
import java.util.UUID;

/**
 * HolderId
 *
 * @param kind
 * @param id
 */
public record HolderId(Kind kind, String id) {

    public HolderId {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
    }

    public boolean isPlayer() {
        return kind == Kind.PLAYER;
    }

    /** {@code KIND/id}: the stable text form used by storage and reports. */
    public String key() {
        return kind.name() + "/" + id;
    }

    /** The origin this player holder stands for as a custodian. */
    public OriginId asPlayerOrigin() {
        if (!isPlayer()) {
            throw new IllegalStateException("not a player holder: " + this);
        }
        return OriginId.player(UUID.fromString(id));
    }

    /** The coordinates of a block holder. */
    public BlockPos blockPos() {
        return BlockPos.parse(id);
    }
}

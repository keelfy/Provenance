package dev.keelfy.provenance.core.model;

/** @author keelfy */
public enum Kind {
    PLAYER,
    BLOCK,
    ENTITY,
    /** A shulker box by the id it keeps when broken and placed again, wherever it stands. */
    SHULKER,
    /** Another server of the network, as the place a synced inventory came from. */
    SERVER
}

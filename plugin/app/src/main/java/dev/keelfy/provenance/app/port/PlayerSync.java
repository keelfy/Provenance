package dev.keelfy.provenance.app.port;

import java.util.UUID;

/**
 * Whether another plugin is moving a player's inventory in or out right now (a proxy network syncing it between
 * servers). While it does, the inventory is not what the player really holds, so reconciling it would burn or mint
 * items that only travel.
 *
 * @author keelfy
 */
@FunctionalInterface
public interface PlayerSync {

    /** No such plugin: the inventory is always the truth. */
    PlayerSync NONE = player -> false;

    boolean isSyncing(UUID player);
}

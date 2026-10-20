package dev.keelfy.provenance.paper.husksync;

import dev.keelfy.provenance.app.port.PlayerSync;
import java.util.UUID;
import net.william278.husksync.api.BukkitHuskSyncAPI;

/**
 * HuskSync locks a player from the moment they join until their synced data is applied, and again once they leave until
 * it is saved.
 *
 * @author keelfy
 */
public final class HuskSyncLock implements PlayerSync {

    private final BukkitHuskSyncAPI api = BukkitHuskSyncAPI.getInstance();

    @Override
    public boolean isSyncing(UUID player) {
        return api.getPlugin().isLocked(player);
    }
}

package dev.keelfy.provenance.paper.husksync;

import dev.keelfy.provenance.app.Settings;
import dev.keelfy.provenance.app.inventory.InventoryReconciler;
import dev.keelfy.provenance.app.port.Log;
import dev.keelfy.provenance.app.port.Scheduler;
import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.paper.PaperPlayer;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.val;
import net.kyori.adventure.key.Key;
import net.william278.husksync.api.BukkitHuskSyncAPI;
import net.william278.husksync.data.Identifier;
import net.william278.husksync.event.BukkitDataSaveEvent;
import net.william278.husksync.event.BukkitSyncCompleteEvent;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Carries the ledger's pools of a player inside the HuskSync snapshot that carries their inventory, so what a player
 * takes from a chest on one server does not turn into {@code unknown} items on the next. Without a {@code server.id} it
 * only waits for the sync to finish before the ledger looks at the inventory.
 *
 * @author keelfy
 */
@RequiredArgsConstructor
public final class HuskSyncBridge implements Listener {

    /** Applied after the inventory and the ender chest, which are what the pools describe. */
    private static final Identifier POOLS = Identifier.from(
            Key.key("provenance", "pools"),
            Set.of(
                    Identifier.Dependency.optional(Key.key("husksync", "inventory")),
                    Identifier.Dependency.optional(Key.key("husksync", "ender_chest"))));

    private final Server server;
    private final Log log;
    private final Settings settings;
    private final Ledger ledger;
    private final InventoryReconciler reconciler;
    private final Scheduler scheduler;

    private final BukkitHuskSyncAPI api = BukkitHuskSyncAPI.getInstance();

    public void register() {
        if (settings.syncEnabled()) {
            api.registerDataSerializer(POOLS, new PoolsSerializer(api.getPlugin(), this::received));
        } else {
            log.info("HuskSync found but server.id is empty: items carried over from other servers show up as unknown");
        }
    }

    /** The inventory has just arrived with the pools it was tracked as on the server it came from. */
    private void received(Player player, PoolsData data) {
        if (!data.server().equals(settings.serverId())) {
            ledger.sync(Holder.player(player.getUniqueId()), Holder.server(data.server()), data.pools());
        }
    }

    /**
     * The inventory is final: make the ledger agree with it (the quit-time check was skipped, HuskSync already held the
     * player), then attach the pools to the snapshot.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDataSave(BukkitDataSaveEvent event) {
        val player = server.getPlayer(event.getUser().getUuid());
        if (player == null) {
            return;
        }

        reconciler.reconcileFinal(PaperPlayer.of(player));
        if (settings.syncEnabled()) {
            event.editData(snapshot -> snapshot.setData(POOLS, pools(player)));
        }
    }

    /**
     * The synced data is in place but HuskSync still holds the player for this tick. Whoever came without pools (the
     * first join, a server without Provenance) is aligned from what they hold, as any player is.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSyncComplete(BukkitSyncCompleteEvent event) {
        val id = event.getUser().getUuid();
        scheduler.runNextTick(() -> reconcile(id));
    }

    private void reconcile(UUID id) {
        val player = server.getPlayer(id);
        if (player != null) {
            reconciler.reconcile(PaperPlayer.of(player));
        }
    }

    /**
     * HuskSync saves on shutdown without asking for edits, from the data the players have already been given: put ours
     * there first.
     */
    public void persistAll() {
        if (!settings.syncEnabled()) {
            return;
        }
        for (val player : server.getOnlinePlayers()) {
            val user = api.getUser(player);
            if (!user.isLocked()) {
                reconciler.reconcileFinal(PaperPlayer.of(player));
                user.getCustomDataStore().put(POOLS, pools(player));
            }
        }
    }

    private PoolsData pools(Player player) {
        return PoolsData.of(settings.serverId(), ledger.snapshot(Holder.player(player.getUniqueId())));
    }
}

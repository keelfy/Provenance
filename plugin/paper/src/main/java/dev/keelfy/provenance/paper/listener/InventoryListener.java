package dev.keelfy.provenance.paper.listener;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import dev.keelfy.provenance.app.inventory.InventoryReconciler;
import dev.keelfy.provenance.app.inventory.ViewTarget;
import dev.keelfy.provenance.core.model.ItemKey;
import dev.keelfy.provenance.paper.ItemKeys;
import dev.keelfy.provenance.paper.PaperPlayer;
import dev.keelfy.provenance.paper.Views;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Hands inventory interactions to the {@link InventoryReconciler}, which settles them by diff. Also owns player
 * reconciliation on join and quit.
 *
 * @author keelfy
 */
@Singleton
@RequiredArgsConstructor(onConstructor = @__(@Inject))
public final class InventoryListener implements Listener {

    private final InventoryReconciler reconciler;

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        track(event.getWhoClicked(), Views.target(event.getView()), Map.of());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        val old = event.getOldCursor();
        val left = event.getWhoClicked().getItemOnCursor();
        val inFlight = reconciler.inFlight(ItemKeys.of(old), old.getAmount(), ItemKeys.of(left), left.getAmount());
        track(event.getWhoClicked(), Views.target(event.getView()), inFlight);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        track(event.getPlayer(), Views.target(event.getView()), Map.of());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        reconciler.reconcile(PaperPlayer.of(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        reconciler.reconcile(PaperPlayer.of(event.getPlayer()));
    }

    private void track(HumanEntity who, ViewTarget target, Map<ItemKey, Long> inFlight) {
        if (who instanceof Player player) {
            reconciler.track(PaperPlayer.of(player), target, inFlight);
        }
    }
}

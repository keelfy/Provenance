package dev.keelfy.provenance.paper.listener;

import java.util.Map;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import com.google.inject.Singleton;
import com.google.inject.Inject;

import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.paper.ViewTarget;
import dev.keelfy.provenance.paper.inventory.InventoryReconciler;
import lombok.RequiredArgsConstructor;
import lombok.val;

/**
 * Settles inventory interactions by diff: holders are reconciled when the first click of a tick arrives,
 * and the next tick's counts are turned into transfers or conversions. Also owns player reconciliation.
 *
 * @author keelfy
 */
@Singleton
@RequiredArgsConstructor(onConstructor = @__(@Inject))
public final class InventoryListener implements Listener {

    private final @NotNull Plugin plugin;
    private final @NotNull Ledger ledger;
    private final @NotNull InventoryReconciler reconciler;
    
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        reconciler.track(event.getWhoClicked(), ViewTarget.of(event.getView()), Map.of());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        val old = event.getOldCursor();
        val left = event.getWhoClicked().getItemOnCursor();
        val inFlight = reconciler.inFlight(old, left);
        reconciler.track(event.getWhoClicked(), ViewTarget.of(event.getView()), inFlight);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        reconciler.track(event.getPlayer(), ViewTarget.of(event.getView()), Map.of());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        reconciler.reconcile(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        reconciler.reconcile(event.getPlayer());
    }

}

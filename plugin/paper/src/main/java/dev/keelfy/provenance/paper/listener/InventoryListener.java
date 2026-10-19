package dev.keelfy.provenance.paper.listener;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import dev.keelfy.provenance.app.inventory.InventoryReconciler;
import dev.keelfy.provenance.app.inventory.ViewTarget;
import dev.keelfy.provenance.app.observe.ItemEvents;
import dev.keelfy.provenance.core.model.ItemKey;
import dev.keelfy.provenance.paper.ItemKeys;
import dev.keelfy.provenance.paper.PaperPlayer;
import dev.keelfy.provenance.paper.Views;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrowableProjectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Hands inventory interactions to the {@link InventoryReconciler}, which settles them by diff. Also owns player
 * reconciliation on join and quit, and items used up (eaten, placed, broken, thrown).
 *
 * @author keelfy
 */
@Singleton
@RequiredArgsConstructor(onConstructor = @__(@Inject))
public final class InventoryListener implements Listener {

    private final InventoryReconciler reconciler;
    private final ItemEvents items;

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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        val item = ItemKeys.of(event.getItem());
        if (item != null) {
            items.consumed(PaperPlayer.of(event.getPlayer()), item);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreakItem(PlayerItemBreakEvent event) {
        val item = ItemKeys.of(event.getBrokenItem());
        if (item != null) {
            items.broken(PaperPlayer.of(event.getPlayer()), item);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        val item = ItemKeys.of(event.getConsumable());
        if (item != null && event.shouldConsumeItem() && event.getEntity() instanceof Player player) {
            items.launched(PaperPlayer.of(player), item);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onThrow(ProjectileLaunchEvent event) {
        if (event.getEntity() instanceof ThrowableProjectile thrown && thrown.getShooter() instanceof Player player) {
            val item = ItemKeys.of(thrown.getItem());
            if (item != null) {
                items.launched(PaperPlayer.of(player), item);
            }
        }
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

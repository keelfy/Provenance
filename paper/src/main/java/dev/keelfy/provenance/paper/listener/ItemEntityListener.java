package dev.keelfy.provenance.paper.listener;

import java.util.Set;

import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import com.google.inject.Singleton;
import com.google.inject.Inject;

import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.paper.Holders;
import dev.keelfy.provenance.paper.ItemKeys;
import dev.keelfy.provenance.paper.Players;
import dev.keelfy.provenance.paper.inventory.InventoryReconciler;
import lombok.RequiredArgsConstructor;
import lombok.val;

/**
 * Items on the ground: each item entity is its own holder from drop to pickup, merge or removal.
 * Mobs that pick items up hold them the same way until they die.
 *
 * @author keelfy
 */
@Singleton 
@RequiredArgsConstructor(onConstructor = @__(@Inject))
public final class ItemEntityListener implements Listener {

    /** Removal causes after which the entity still exists somewhere and keeps its pool. */
    private static final Set<EntityRemoveEvent.Cause> PERSISTENT =
            Set.of(EntityRemoveEvent.Cause.UNLOAD, EntityRemoveEvent.Cause.PLAYER_QUIT);

    private final @NotNull Plugin plugin;
    private final @NotNull Ledger ledger;
    private final @NotNull InventoryReconciler reconciler;

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        val player = event.getPlayer();
        val drop = event.getItemDrop();
        val dropped = ItemKeys.count(drop.getItemStack());
        if (dropped.isEmpty()) {
            return;
        }

        if (!reconciler.isSettling(player)) {
            // The stack has already left the inventory; reconcile against the state before the drop.
            val before = Players.counts(player);
            dropped.forEach((item, amount) -> before.merge(item, amount, Long::sum));
            ledger.reconcile(Holders.of(player), before, Players.mintOrigin(player));
        }
        ledger.transfer(Holders.of(player), Holders.of(drop), dropped);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        val item = event.getItem();
        val picked = item.getItemStack().getAmount() - event.getRemaining();
        if (ItemKeys.of(item.getItemStack()) == null || picked <= 0) {
            return;
        }

        val picker = event.getEntity();
        if (picker instanceof Player player) {
            reconciler.reconcile(player);
        }
        ledger.transfer(Holders.of(item), Holders.of(picker), ItemKeys.count(item.getItemStack().asQuantity(picked)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMerge(ItemMergeEvent event) {
        val source = event.getEntity();
        val target = event.getTarget();
        val room = target.getItemStack().getMaxStackSize() - target.getItemStack().getAmount();
        val moved = Math.min(source.getItemStack().getAmount(), room);
        if (ItemKeys.of(source.getItemStack()) == null || moved <= 0) {
            return;
        }

        ledger.transfer(Holders.of(source), Holders.of(target), ItemKeys.count(source.getItemStack().asQuantity(moved)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveEvent event) {
        val entity = event.getEntity();
        if (entity instanceof Player || PERSISTENT.contains(event.getCause())) {
            return;
        }
        val holder = Holders.of(entity);
        if (entity instanceof Hanging) {
            // A frame is removed before its item spawns; the spill still needs the pool.
            plugin.getServer().getScheduler().runTask(plugin, () -> ledger.clear(holder));
            return;
        }
        ledger.clear(holder);
    }
}

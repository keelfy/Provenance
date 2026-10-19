package dev.keelfy.provenance.app.observe;

import dev.keelfy.provenance.app.inventory.InventoryReconciler;
import dev.keelfy.provenance.app.port.OnlinePlayer;
import dev.keelfy.provenance.app.port.Scheduler;
import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.core.model.Cause;
import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.ItemKey;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.val;

/**
 * Items on the ground: each item entity is its own holder from drop to pickup, merge or removal. Mobs that pick items
 * up hold them the same way until they die.
 *
 * @author keelfy
 */
@RequiredArgsConstructor
public final class ItemEvents {

    private final Ledger ledger;
    private final InventoryReconciler reconciler;
    private final Scheduler scheduler;

    /** @param dropped what the item entity carries; the stack has already left the player's inventory */
    public void dropped(OnlinePlayer player, Holder entity, Map<ItemKey, Long> dropped) {
        if (dropped.isEmpty()) {
            return;
        }

        if (!reconciler.isSettling(player)) {
            // Reconcile against the state before the drop.
            val before = player.holdings();
            dropped.forEach((item, amount) -> before.merge(item, amount, Long::sum));
            ledger.reconcile(Cause.RECONCILE, player.holder(), before, player.mintOrigin());
        }
        ledger.transfer(Cause.DROP, player.holder(), entity, dropped);
    }

    public void pickedUp(Holder item, OnlinePlayer picker, Map<ItemKey, Long> picked) {
        if (picked.isEmpty()) {
            return;
        }
        reconciler.reconcile(picker);
        pickedUp(item, picker.holder(), picked);
    }

    public void pickedUp(Holder item, Holder picker, Map<ItemKey, Long> picked) {
        if (!picked.isEmpty()) {
            ledger.transfer(Cause.PICKUP, item, picker, picked);
        }
    }

    /** An item entity merged into another one; {@code moved} is what fitted. */
    public void merged(Holder source, Holder target, Map<ItemKey, Long> moved) {
        if (!moved.isEmpty()) {
            ledger.transfer(Cause.DROP, source, target, moved);
        }
    }

    /**
     * An entity that held items is gone.
     *
     * @param spillPending the entity is removed before its item spawns (an item frame), so the pool is needed a tick
     *     longer
     */
    public void removed(Holder entity, boolean spillPending) {
        if (spillPending) {
            scheduler.runNextTick(() -> ledger.clear(Cause.DROP, entity));
        } else {
            ledger.clear(Cause.DROP, entity);
        }
    }
}

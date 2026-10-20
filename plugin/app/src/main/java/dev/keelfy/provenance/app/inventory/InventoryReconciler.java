package dev.keelfy.provenance.app.inventory;

import dev.keelfy.provenance.app.Settings;
import dev.keelfy.provenance.app.port.OnlinePlayer;
import dev.keelfy.provenance.app.port.PlayerDirectory;
import dev.keelfy.provenance.app.port.PlayerSync;
import dev.keelfy.provenance.app.port.Scheduler;
import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.core.Settlement;
import dev.keelfy.provenance.core.model.Cause;
import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.ItemKey;
import dev.keelfy.provenance.core.model.OriginId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.val;
import org.jspecify.annotations.Nullable;

/** @author keelfy */
public final class InventoryReconciler {

    private final Scheduler scheduler;
    private final PlayerDirectory players;
    private final Ledger ledger;
    private final Settings settings;
    private final PlayerSync sync;
    private final Set<UUID> pending = new HashSet<>();
    private final Map<UUID, List<Drop>> drops = new HashMap<>();

    private record Drop(Holder entity, Map<ItemKey, Long> items) {}

    public InventoryReconciler(
            Scheduler scheduler, PlayerDirectory players, Ledger ledger, Settings settings, PlayerSync sync) {
        this.scheduler = scheduler;
        this.players = players;
        this.ledger = ledger;
        this.settings = settings;
        this.sync = sync;
    }

    public void runScheduler() {
        scheduler.runEvery(settings.reconcileTicks(), settings.reconcileTicks(), this::scheduled);
    }

    private void scheduled() {
        players.online().forEach(this::reconcile);
    }

    /**
     * Aligns the ledger with the player's actual items unless an interaction is being settled this tick or the
     * inventory is being synced.
     */
    public void reconcile(OnlinePlayer player) {
        reconcile(player, Map.of());
    }

    public void reconcile(OnlinePlayer player, Map<ItemKey, Long> inFlight) {
        if (!sync.isSyncing(player.id())) {
            align(player, inFlight);
        }
    }

    /**
     * Like {@link #reconcile(OnlinePlayer)} but also while the sync plugin holds the player, for when it says the
     * inventory is final: it is about to be saved and sent on to another server.
     */
    public void reconcileFinal(OnlinePlayer player) {
        align(player, Map.of());
    }

    private void align(OnlinePlayer player, Map<ItemKey, Long> inFlight) {
        if (!pending.contains(player.id())) {
            val actual = player.holdings();
            inFlight.forEach((item, amount) -> actual.merge(item, amount, (a, b) -> a + b));
            ledger.reconcile(Cause.RECONCILE, player.holder(), actual, player.mintOrigin());
        }
    }

    /**
     * One item of the player's is used up (eaten, placed as a block). The platform reports it before the stack shrinks,
     * so the ledger is aligned first and the burn then carries the reason; creative players keep their stack.
     */
    public void spend(OnlinePlayer player, Cause cause, ItemKey item) {
        if (player.creative()) {
            return;
        }
        reconcile(player);
        ledger.burn(cause, player.holder(), item, 1);
    }

    /**
     * The server shrinks the cursor before firing a drag event and fills the slots after it, so the dragged items are
     * momentarily in neither place.
     *
     * @param old the item on the cursor before the drag, {@code null} if it was empty
     * @param left the item on the cursor after the drag, {@code null} if it is empty
     */
    public Map<ItemKey, Long> inFlight(@Nullable ItemKey old, int oldAmount, @Nullable ItemKey left, int leftAmount) {
        val moved = oldAmount - (old != null && old.equals(left) ? leftAmount : 0);
        return old != null && moved > 0 ? Map.of(old, (long) moved) : Map.of();
    }

    /** Settles an interaction with a block that holds items without opening a view, e.g. a jukebox. */
    public void track(OnlinePlayer player, ViewTarget target) {
        track(player, target, Map.of());
    }

    public void track(OnlinePlayer player, ViewTarget target, Map<ItemKey, Long> inFlight) {
        if (isSettling(player) || sync.isSyncing(player.id())) {
            return;
        }

        reconcile(player, inFlight);
        val container = target.container();
        if (container != null) {
            ledger.reconcile(Cause.RECONCILE, container, target.contents().get());
        }

        pending.add(player.id());
        scheduler.runNextTick(() -> {
            pending.remove(player.id());
            settle(player, target);
        });
    }

    public boolean isSettling(OnlinePlayer player) {
        return pending.contains(player.id());
    }

    /**
     * An item entity was dropped while the player's interaction is still being settled, e.g. a stack thrown straight
     * out of a chest. The items have not reached the player's ledger account yet, so the drop is booked after the
     * settlement, once they have.
     *
     * @return {@code false} if nothing is being settled and the caller books the drop itself
     */
    public boolean deferDrop(OnlinePlayer player, Holder entity, Map<ItemKey, Long> dropped) {
        if (!isSettling(player)) {
            return false;
        }
        drops.computeIfAbsent(player.id(), id -> new ArrayList<>()).add(new Drop(entity, dropped));
        return true;
    }

    private void settle(OnlinePlayer player, ViewTarget target) {
        val holder = player.holder();
        val actual = player.holdings();
        val dropped = drops.getOrDefault(player.id(), List.of());
        drops.remove(player.id());
        // what was thrown out counts as still held until the drop is booked below
        dropped.forEach(drop -> drop.items().forEach((item, amount) -> actual.merge(item, amount, Long::sum)));

        switch (target.kind()) {
            case CONTAINER ->
                new Settlement(ledger, Cause.CONTAINER, player.mintOrigin())
                        .container(
                                holder,
                                actual,
                                target.container(),
                                target.contents().get());
            case WORKSTATION -> new Settlement(ledger, Cause.CRAFT, player.mintOrigin()).workstation(holder, actual);
            case MERCHANT -> ledger.reconcile(Cause.TRADE, holder, actual, OriginId.TRADE);
            case SELF -> ledger.reconcile(Cause.RECONCILE, holder, actual, player.mintOrigin());
        }

        dropped.forEach(drop -> ledger.transfer(Cause.DROP, holder, drop.entity(), drop.items()));
    }
}

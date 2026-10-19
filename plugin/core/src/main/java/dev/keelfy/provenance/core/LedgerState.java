package dev.keelfy.provenance.core;

import dev.keelfy.provenance.core.event.LedgerEvent;
import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.ItemKey;
import dev.keelfy.provenance.core.taint.TaintVector;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

/**
 * Everything derived from the event stream, assembled from its {@link Projection}s. Changes only through
 * {@link #apply}. Not thread-safe.
 *
 * @author keelfy
 */
final class LedgerState {

    @Getter
    private final FlowGraph flows = new FlowGraph();

    private final PoolProjection pools = new PoolProjection();

    // The flow projection reads the pools as they were before the event, so it goes first.
    private final List<Projection> projections = List.of(new FlowProjection(flows, pools), pools);

    void apply(LedgerEvent event) {
        projections.forEach(projection -> projection.apply(event));
    }

    @Nullable Pool pool(Holder holder, ItemKey item) {
        return pools.pool(holder, item);
    }

    long getCount(Holder holder, ItemKey item) {
        return pools.getCount(holder, item);
    }

    Map<ItemKey, Pool> getPools(Holder holder) {
        return pools.getPools(holder);
    }

    Set<Holder> getHolders() {
        return pools.getHolders();
    }

    long movable(Holder holder, ItemKey item, long amount) {
        return pools.movable(holder, item, amount);
    }

    /** Who a transfer of this item out of {@code from} would attribute custody to; empty if nothing is tracked. */
    TaintVector custodyOf(Holder from, ItemKey item) {
        return pools.custodyOf(from, item);
    }

    /** Current pools of every holder changed since the previous call; the dirty set starts over. */
    Map<Holder, List<LedgerChanges.PoolRow>> drainDirty() {
        return pools.drainDirty();
    }

    /** Loads persisted state without marking it dirty. */
    void restore(Holder holder, LedgerChanges.PoolRow row) {
        pools.restore(holder, row);
    }
}

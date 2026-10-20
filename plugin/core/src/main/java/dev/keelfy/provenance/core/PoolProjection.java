package dev.keelfy.provenance.core;

import dev.keelfy.provenance.core.event.LedgerEvent;
import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.ItemKey;
import dev.keelfy.provenance.core.model.OriginId;
import dev.keelfy.provenance.core.taint.TaintVector;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.val;
import org.jspecify.annotations.Nullable;

/**
 * The projection of the event stream into holder × item → pool, with the dirty set the persistence layer drains.
 * Changes only through {@link #apply}. Not thread-safe.
 *
 * @author keelfy
 */
final class PoolProjection implements Projection {

    private final Map<Holder, Map<ItemKey, Pool>> holders = new HashMap<>();
    private final Set<Holder> dirty = new HashSet<>();

    public @Nullable Pool pool(Holder holder, ItemKey item) {
        return holders.getOrDefault(holder, Map.of()).get(item);
    }

    public long getCount(Holder holder, ItemKey item) {
        val pool = pool(holder, item);
        return pool != null ? pool.getCount() : 0L;
    }

    public Map<ItemKey, Pool> getPools(Holder holder) {
        return Collections.unmodifiableMap(holders.getOrDefault(holder, Map.of()));
    }

    public Set<Holder> getHolders() {
        return Collections.unmodifiableSet(holders.keySet());
    }

    /** A player is the only custodian of what they hold; anywhere else custody is whatever came with the items. */
    private static TaintVector custodyAt(Holder holder, TaintVector carried) {
        return holder.isPlayer() ? TaintVector.of(holder.asPlayerOrigin()) : carried;
    }

    /** How much of {@code amount} the holder can actually give up: capped at the tracked count. */
    long movable(Holder holder, ItemKey item, long amount) {
        return Math.min(amount, getCount(holder, item));
    }

    /** Who a transfer of this item out of {@code from} would attribute custody to; empty if nothing is tracked. */
    TaintVector custodyOf(Holder from, ItemKey item) {
        val pool = pool(from, item);
        if (pool == null) {
            return TaintVector.EMPTY;
        }
        return custodyAt(from, pool.getCustody());
    }

    @Override
    @SuppressWarnings("java:S6878") // switch on sealed class
    public void apply(LedgerEvent event) {
        switch (event) {
            case LedgerEvent.Minted e -> mint(e.holder(), e.item(), e.amount(), e.origin());
            case LedgerEvent.Transferred e -> transfer(e);
            case LedgerEvent.Burned e -> burn(e.holder(), e.item(), e.amount());
            case LedgerEvent.Converted e -> convert(e);
            case LedgerEvent.Cleared e -> clear(e.holder());
            case LedgerEvent.Synced e -> sync(e);
        }
    }

    private void clear(Holder holder) {
        if (holders.remove(holder) != null) {
            dirty.add(holder);
        }
    }

    private void sync(LedgerEvent.Synced e) {
        clear(e.holder());
        e.pools().forEach((item, state) -> {
            if (state.count() > 0) {
                restore(e.holder(), new LedgerChanges.PoolRow(item, state.count(), state.origin(), state.custody()));
                dirty.add(e.holder());
            }
        });
    }

    private void mint(Holder holder, ItemKey item, long amount, OriginId origin) {
        if (amount <= 0) {
            return;
        }
        val originVector = TaintVector.of(origin);
        poolFor(holder, item).add(amount, originVector, custodyAt(holder, originVector));
    }

    private long burn(Holder holder, ItemKey item, long amount) {
        val pool = pool(holder, item);
        if (pool == null || amount <= 0) {
            return 0;
        }

        val removed = movable(holder, item, amount);
        dirty.add(holder);
        pool.remove(removed);
        dropIfEmpty(holder, item, pool);
        return removed;
    }

    private void transfer(LedgerEvent.Transferred e) {
        val source = pool(e.holder(), e.item());
        if (source == null) {
            return;
        }

        val moved = movable(e.holder(), e.item(), e.amount());
        if (moved <= 0) {
            return;
        }

        dirty.add(e.holder());
        deposit(e.to(), e.item(), moved, source.getOrigin(), custodyOf(e.holder(), e.item()));
        source.remove(moved);
        dropIfEmpty(e.holder(), e.item(), source);
    }

    private void convert(LedgerEvent.Converted e) {
        val holder = e.holder();
        val origin = TaintVector.builder();
        val custody = TaintVector.builder();

        e.inputs().forEach((item, amount) -> {
            val pool = pool(holder, item);
            val known = burn(holder, item, amount);
            if (pool != null && known > 0) {
                origin.add(pool.getOrigin(), known);
                custody.add(pool.getCustody(), known);
            }
            origin.add(OriginId.UNKNOWN, amount - known);
            custody.add(OriginId.UNKNOWN, amount - known);
        });

        var outOrigin = origin.build();
        if (outOrigin.isEmpty()) {
            outOrigin = TaintVector.of(OriginId.UNKNOWN);
        }

        var outCustody = custodyAt(holder, custody.build());
        if (outCustody.isEmpty()) {
            outCustody = outOrigin;
        }

        for (val output : e.outputs().entrySet()) {
            if (output.getValue() > 0) {
                poolFor(holder, output.getKey()).add(output.getValue(), outOrigin, outCustody);
            }
        }
    }

    private void deposit(Holder to, ItemKey item, long amount, TaintVector origin, TaintVector custody) {
        poolFor(to, item).add(amount, origin, custodyAt(to, custody));
    }

    private Pool poolFor(Holder holder, ItemKey item) {
        dirty.add(holder);
        return holders.computeIfAbsent(holder, k -> new HashMap<>())
                .computeIfAbsent(item, k -> new Pool(0, TaintVector.EMPTY, TaintVector.EMPTY));
    }

    private void dropIfEmpty(Holder holder, ItemKey item, Pool pool) {
        if (pool.getCount() > 0) {
            return;
        }

        val pools = holders.get(holder);
        pools.remove(item);
        if (pools.isEmpty()) {
            holders.remove(holder);
        }
    }

    /** Current pools of every holder changed since the previous call; the dirty set starts over. */
    Map<Holder, List<LedgerChanges.PoolRow>> drainDirty() {
        val changed = new HashMap<Holder, List<LedgerChanges.PoolRow>>();
        for (val holder : dirty) {
            val rows = new ArrayList<LedgerChanges.PoolRow>();
            holders.getOrDefault(holder, Map.of())
                    .forEach((item, pool) -> rows.add(
                            new LedgerChanges.PoolRow(item, pool.getCount(), pool.getOrigin(), pool.getCustody())));
            changed.put(holder, rows);
        }
        dirty.clear();
        return changed;
    }

    /** Loads persisted state without marking it dirty. */
    void restore(Holder holder, LedgerChanges.PoolRow row) {
        holders.computeIfAbsent(holder, k -> new HashMap<>())
                .put(row.item(), new Pool(row.count(), row.origin(), row.custody()));
    }
}

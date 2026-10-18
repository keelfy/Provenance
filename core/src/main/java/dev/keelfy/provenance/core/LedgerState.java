package dev.keelfy.provenance.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.val;
import org.jspecify.annotations.Nullable;

/**
 * The projection of the event stream: holder × item → pool plus the flow graph. Changes only through {@link #apply}.
 * Not thread-safe.
 *
 * @author keelfy
 */
public final class LedgerState {

    @Getter
    private final FlowGraph flows = new FlowGraph();

    private final Map<HolderId, Map<ItemKey, Pool>> holders = new HashMap<>();
    private final Set<HolderId> dirty = new HashSet<>();

    public @Nullable Pool pool(HolderId holder, ItemKey item) {
        return holders.getOrDefault(holder, Map.of()).get(item);
    }

    public long getCount(HolderId holder, ItemKey item) {
        val pool = pool(holder, item);
        return pool != null ? pool.getCount() : 0L;
    }

    public Map<ItemKey, Pool> getPools(HolderId holder) {
        return Collections.unmodifiableMap(holders.getOrDefault(holder, Map.of()));
    }

    public Set<HolderId> getHolders() {
        return Collections.unmodifiableSet(holders.keySet());
    }

    /** Who a transfer of this item out of {@code from} would attribute custody to; empty if nothing is tracked. */
    TaintVector custodyOf(HolderId from, ItemKey item) {
        val pool = pool(from, item);
        if (pool == null) {
            return TaintVector.EMPTY;
        }
        return from.isPlayer() ? TaintVector.of(from.asPlayerOrigin()) : pool.getCustody();
    }

    public void apply(LedgerEvent event) {
        switch (event) {
            case LedgerEvent.Minted e -> mint(e.holder(), e.item(), e.amount(), e.origin());
            case LedgerEvent.Transferred e -> transfer(e);
            case LedgerEvent.Burned e -> burn(e.holder(), e.item(), e.amount());
            case LedgerEvent.Converted e -> convert(e);
            case LedgerEvent.Cleared e -> {
                if (holders.remove(e.holder()) != null) {
                    dirty.add(e.holder());
                }
            }
        }
    }

    private void mint(HolderId holder, ItemKey item, long amount, OriginId origin) {
        if (amount <= 0) {
            return;
        }
        val originVector = TaintVector.of(origin);
        val custody = holder.isPlayer() ? TaintVector.of(holder.asPlayerOrigin()) : originVector;
        poolFor(holder, item).add(amount, originVector, custody);
    }

    private long burn(HolderId holder, ItemKey item, long amount) {
        val pool = pool(holder, item);
        if (pool == null || amount <= 0) {
            return 0;
        }

        val removed = Math.min(amount, pool.getCount());
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

        val moved = Math.min(e.amount(), source.getCount());
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
            if (known > 0) {
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

        var outCustody = holder.isPlayer() ? TaintVector.of(holder.asPlayerOrigin()) : custody.build();
        if (outCustody.isEmpty()) {
            outCustody = outOrigin;
        }

        for (val output : e.outputs().entrySet()) {
            if (output.getValue() > 0) {
                poolFor(holder, output.getKey()).add(output.getValue(), outOrigin, outCustody);
            }
        }
    }

    private void deposit(HolderId to, ItemKey item, long amount, TaintVector origin, TaintVector custody) {
        if (to.isPlayer()) {
            val receiver = to.asPlayerOrigin();
            custody.shares().forEach((custodian, share) -> {
                if (custodian.isPlayer()) {
                    flows.record(custodian, receiver, item, share * amount);
                }
            });
            custody = TaintVector.of(receiver);
        }
        poolFor(to, item).add(amount, origin, custody);
    }

    private Pool poolFor(HolderId holder, ItemKey item) {
        dirty.add(holder);
        return holders.computeIfAbsent(holder, k -> new HashMap<>())
                .computeIfAbsent(item, k -> new Pool(0, TaintVector.EMPTY, TaintVector.EMPTY));
    }

    private void dropIfEmpty(HolderId holder, ItemKey item, Pool pool) {
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
    Map<HolderId, List<LedgerChanges.PoolRow>> drainDirty() {
        val changed = new HashMap<HolderId, List<LedgerChanges.PoolRow>>();
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
    void restore(HolderId holder, LedgerChanges.PoolRow row) {
        holders.computeIfAbsent(holder, k -> new HashMap<>())
                .put(row.item(), new Pool(row.count(), row.origin(), row.custody()));
    }
}

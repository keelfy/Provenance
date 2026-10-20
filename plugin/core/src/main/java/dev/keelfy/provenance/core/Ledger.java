package dev.keelfy.provenance.core;

import dev.keelfy.provenance.core.event.Envelope;
import dev.keelfy.provenance.core.event.LedgerEvent;
import dev.keelfy.provenance.core.event.PoolState;
import dev.keelfy.provenance.core.model.Cause;
import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.ItemKey;
import dev.keelfy.provenance.core.model.OriginId;
import dev.keelfy.provenance.core.taint.TaintVector;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import lombok.val;
import org.jspecify.annotations.Nullable;

/**
 * Account-model ledger: holder × item → pool. Not thread-safe; owned by the server main thread. Commands decide what
 * happened and emit {@link LedgerEvent}s; {@link LedgerState#apply} is the only mutator. Every operation conserves
 * counts except {@link #mint} and {@link #burn}; shortfalls are minted as {@link OriginId#UNKNOWN}.
 *
 * @author keelfy
 */
public final class Ledger implements LedgerView {

    private static final Comparator<ItemKey> BY_KEY = Comparator.comparing(ItemKey::key);

    private final LedgerState state = new LedgerState();
    private final Journal journal;

    public Ledger() {
        this(System::currentTimeMillis);
    }

    public Ledger(LongSupplier clock) {
        this(clock, true);
    }

    /** @param mergeAutomation journal back-to-back automation moves of one item as a single event */
    public Ledger(LongSupplier clock, boolean mergeAutomation) {
        this.journal = new Journal(clock, mergeAutomation);
    }

    @Override
    public FlowGraph getFlows() {
        return state.getFlows();
    }

    @Override
    public @Nullable Pool pool(Holder holder, ItemKey item) {
        return state.pool(holder, item);
    }

    @Override
    public long getCount(Holder holder, ItemKey item) {
        return state.getCount(holder, item);
    }

    @Override
    public Map<ItemKey, Pool> getPools(Holder holder) {
        return state.getPools(holder);
    }

    @Override
    public Set<Holder> getHolders() {
        return state.getHolders();
    }

    public void mint(Cause cause, Holder holder, ItemKey item, long amount, OriginId origin) {
        if (amount > 0) {
            emit(cause, new LedgerEvent.Minted(holder, item, amount, origin));
        }
    }

    /** @return amount actually removed (capped at the tracked count) */
    public long burn(Cause cause, Holder holder, ItemKey item, long amount) {
        val removed = state.movable(holder, item, amount);
        if (removed > 0) {
            emit(cause, new LedgerEvent.Burned(holder, item, removed));
        }
        return Math.max(removed, 0);
    }

    /** Removes everything a holder has, e.g. a despawned item entity or a destroyed container. */
    public void clear(Cause cause, Holder holder) {
        if (state.getHolders().contains(holder)) {
            emit(cause, new LedgerEvent.Cleared(holder));
        }
    }

    /**
     * The pools of {@code holder} as values, ordered by item key: what another server's ledger takes over in
     * {@link #sync}.
     */
    public Map<ItemKey, PoolState> snapshot(Holder holder) {
        val result = new TreeMap<ItemKey, PoolState>(BY_KEY);
        getPools(holder)
                .forEach((item, pool) ->
                        result.put(item, new PoolState(pool.getCount(), pool.getOrigin(), pool.getCustody())));
        return Collections.unmodifiableMap(result);
    }

    /**
     * Replaces everything {@code player} holds with {@code pools} that were tracked on server {@code from}, so items
     * that crossed servers keep their origin instead of reappearing as {@link OriginId#UNKNOWN}.
     */
    public void sync(Holder player, Holder from, Map<ItemKey, PoolState> pools) {
        if (!pools.isEmpty() || state.getHolders().contains(player)) {
            val ordered = new TreeMap<ItemKey, PoolState>(BY_KEY);
            ordered.putAll(pools);
            emit(Cause.SYNC, new LedgerEvent.Synced(player, from, Collections.unmodifiableMap(ordered)));
        }
    }

    /** Snapshot of everything changed since the previous call; dirty tracking and the pending journal start over. */
    public LedgerChanges drainChanges() {
        return new LedgerChanges(state.drainDirty(), state.getFlows().drainDirty(), journal.drain(), journal.getSeq());
    }

    /** Loads persisted state without marking it dirty. */
    public void restore(Holder holder, LedgerChanges.PoolRow row) {
        state.restore(holder, row);
    }

    public void restore(LedgerChanges.FlowRow row) {
        state.getFlows().restore(row.from(), row.to(), row.item(), row.amount());
    }

    /** Sequence number the restored snapshot reflects; the next event gets {@code lastSeq + 1}. */
    public void restoreSeq(long lastSeq) {
        journal.resumeAfter(lastSeq);
    }

    /**
     * Re-applies a journaled event after a restart. Unlike a fresh command it is not journaled again, but the touched
     * holders become dirty so the next flush snapshots them.
     */
    public void replay(Envelope envelope) {
        state.apply(envelope.event());
        journal.resumeAfter(envelope.seq());
    }

    /**
     * Moves {@code amount} items with haircut semantics. Player-to-player custody changes are recorded as flows. If
     * {@code from} tracks fewer items than moved, the shortfall arrives in {@code to} as {@link OriginId#UNKNOWN}.
     */
    public void transfer(Cause cause, Holder from, Holder to, ItemKey item, long amount) {
        if (amount <= 0 || from.equals(to)) {
            return;
        }

        val moved = state.movable(from, item, amount);
        if (moved > 0) {
            val custody = to.isPlayer() ? state.custodyOf(from, item) : TaintVector.EMPTY;
            emit(cause, new LedgerEvent.Transferred(from, to, item, moved, custody));
        }
        mint(cause, to, item, amount - moved, OriginId.UNKNOWN);
    }

    /** Moves several items at once, e.g. a stack together with what it carries inside. */
    public void transfer(Cause cause, Holder from, Holder to, Map<ItemKey, Long> items) {
        sorted(items).forEach((item, amount) -> transfer(cause, from, to, item, amount));
    }

    /** Moves every pool of {@code from} into {@code to}, e.g. when a container's holder id changes. */
    public void transferAll(Cause cause, Holder from, Holder to) {
        val counts = new HashMap<ItemKey, Long>();
        getPools(from).forEach((item, pool) -> counts.put(item, pool.getCount()));
        transfer(cause, from, to, counts);
    }

    /**
     * Crafting/smelting inside one holder: inputs are consumed, outputs inherit the count-weighted mix of input
     * vectors. Inputs the ledger does not know about contribute {@link OriginId#UNKNOWN}.
     */
    public void convert(Cause cause, Holder holder, Map<ItemKey, Long> inputs, Map<ItemKey, Long> outputs) {
        val in = sorted(inputs);
        val out = sorted(outputs);
        if (!in.isEmpty() || !out.isEmpty()) {
            emit(cause, new LedgerEvent.Converted(holder, in, out));
        }
    }

    /**
     * Aligns tracked counts with reality: missing items are burned, unexplained items minted as
     * {@link OriginId#UNKNOWN}.
     */
    public void reconcile(Cause cause, Holder holder, Map<ItemKey, Long> actual) {
        reconcile(cause, holder, actual, OriginId.UNKNOWN);
    }

    /** Like {@link #reconcile(Cause, Holder, Map)} but unexplained items are attributed to {@code mintOrigin}. */
    public void reconcile(Cause cause, Holder holder, Map<ItemKey, Long> actual, OriginId mintOrigin) {
        sorted(delta(holder, actual)).forEach((item, diff) -> {
            if (diff > 0) {
                mint(cause, holder, item, diff, mintOrigin);
            } else {
                burn(cause, holder, item, -diff);
            }
        });
    }

    /** Non-zero differences {@code actual - tracked} per item. */
    public Map<ItemKey, Long> delta(Holder holder, Map<ItemKey, Long> actual) {
        val items = new HashSet<ItemKey>(actual.keySet());
        items.addAll(getPools(holder).keySet());

        val result = new HashMap<ItemKey, Long>();
        for (val item : items) {
            val diff = actual.getOrDefault(item, 0L) - getCount(holder, item);
            if (diff != 0) {
                result.put(item, diff);
            }
        }
        return result;
    }

    private void emit(Cause cause, LedgerEvent event) {
        state.apply(event);
        journal.append(cause, event);
    }

    /** Positive entries ordered by item key, so events do not depend on hash iteration order. */
    private static Map<ItemKey, Long> sorted(Map<ItemKey, Long> items) {
        val result = new TreeMap<ItemKey, Long>(BY_KEY);
        items.forEach((item, amount) -> {
            if (amount != 0) {
                result.put(item, amount);
            }
        });
        return Collections.unmodifiableMap(result);
    }
}

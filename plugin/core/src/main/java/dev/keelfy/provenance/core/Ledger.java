package dev.keelfy.provenance.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
public final class Ledger {

    private static final Comparator<ItemKey> BY_KEY = Comparator.comparing(ItemKey::key);

    private final LedgerState state = new LedgerState();
    private final LongSupplier clock;
    private final boolean mergeAutomation;
    private final List<Envelope> pending = new ArrayList<>();
    private long seq;

    public Ledger() {
        this(System::currentTimeMillis);
    }

    public Ledger(LongSupplier clock) {
        this(clock, true);
    }

    /** @param mergeAutomation journal back-to-back automation moves of one item as a single event */
    public Ledger(LongSupplier clock, boolean mergeAutomation) {
        this.clock = clock;
        this.mergeAutomation = mergeAutomation;
    }

    public FlowGraph getFlows() {
        return state.getFlows();
    }

    public @Nullable Pool pool(HolderId holder, ItemKey item) {
        return state.pool(holder, item);
    }

    public long getCount(HolderId holder, ItemKey item) {
        return state.getCount(holder, item);
    }

    public Map<ItemKey, Pool> getPools(HolderId holder) {
        return state.getPools(holder);
    }

    public Set<HolderId> getHolders() {
        return state.getHolders();
    }

    public void mint(Cause cause, HolderId holder, ItemKey item, long amount, OriginId origin) {
        if (amount > 0) {
            emit(cause, new LedgerEvent.Minted(holder, item, amount, origin));
        }
    }

    /** @return amount actually removed (capped at the tracked count) */
    public long burn(Cause cause, HolderId holder, ItemKey item, long amount) {
        val removed = Math.min(amount, getCount(holder, item));
        if (removed > 0) {
            emit(cause, new LedgerEvent.Burned(holder, item, removed));
        }
        return Math.max(removed, 0);
    }

    /** Removes everything a holder has, e.g. a despawned item entity or a destroyed container. */
    public void clear(Cause cause, HolderId holder) {
        if (state.getHolders().contains(holder)) {
            emit(cause, new LedgerEvent.Cleared(holder));
        }
    }

    /** Snapshot of everything changed since the previous call; dirty tracking and the pending journal start over. */
    public LedgerChanges drainChanges() {
        val events = List.copyOf(pending);
        pending.clear();
        return new LedgerChanges(state.drainDirty(), state.getFlows().drainDirty(), events, seq);
    }

    /** Loads persisted state without marking it dirty. */
    public void restore(HolderId holder, LedgerChanges.PoolRow row) {
        state.restore(holder, row);
    }

    public void restore(LedgerChanges.FlowRow row) {
        state.getFlows().restore(row.from(), row.to(), row.item(), row.amount());
    }

    /** Sequence number the restored snapshot reflects; the next event gets {@code lastSeq + 1}. */
    public void restoreSeq(long lastSeq) {
        seq = lastSeq;
    }

    /**
     * Re-applies a journaled event after a restart. Unlike a fresh command it is not journaled again, but the touched
     * holders become dirty so the next flush snapshots them.
     */
    public void replay(Envelope envelope) {
        state.apply(envelope.event());
        seq = envelope.seq();
    }

    /**
     * Moves {@code amount} items with haircut semantics. Player-to-player custody changes are recorded as flows. If
     * {@code from} tracks fewer items than moved, the shortfall arrives in {@code to} as {@link OriginId#UNKNOWN}.
     */
    public void transfer(Cause cause, HolderId from, HolderId to, ItemKey item, long amount) {
        if (amount <= 0 || from.equals(to)) {
            return;
        }

        val moved = Math.min(amount, getCount(from, item));
        if (moved > 0) {
            val custody = to.isPlayer() ? state.custodyOf(from, item) : TaintVector.EMPTY;
            emit(cause, new LedgerEvent.Transferred(from, to, item, moved, custody));
        }
        mint(cause, to, item, amount - moved, OriginId.UNKNOWN);
    }

    /** Moves several items at once, e.g. a stack together with what it carries inside. */
    public void transfer(Cause cause, HolderId from, HolderId to, Map<ItemKey, Long> items) {
        sorted(items).forEach((item, amount) -> transfer(cause, from, to, item, amount));
    }

    /** Moves every pool of {@code from} into {@code to}, e.g. when a container's holder id changes. */
    public void transferAll(Cause cause, HolderId from, HolderId to) {
        val counts = new HashMap<ItemKey, Long>();
        getPools(from).forEach((item, pool) -> counts.put(item, pool.getCount()));
        transfer(cause, from, to, counts);
    }

    /**
     * Crafting/smelting inside one holder: inputs are consumed, outputs inherit the count-weighted mix of input
     * vectors. Inputs the ledger does not know about contribute {@link OriginId#UNKNOWN}.
     */
    public void convert(Cause cause, HolderId holder, Map<ItemKey, Long> inputs, Map<ItemKey, Long> outputs) {
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
    public void reconcile(Cause cause, HolderId holder, Map<ItemKey, Long> actual) {
        reconcile(cause, holder, actual, OriginId.UNKNOWN);
    }

    /** Like {@link #reconcile(Cause, HolderId, Map)} but unexplained items are attributed to {@code mintOrigin}. */
    public void reconcile(Cause cause, HolderId holder, Map<ItemKey, Long> actual, OriginId mintOrigin) {
        sorted(delta(holder, actual)).forEach((item, diff) -> {
            if (diff > 0) {
                mint(cause, holder, item, diff, mintOrigin);
            } else {
                burn(cause, holder, item, -diff);
            }
        });
    }

    /** Non-zero differences {@code actual - tracked} per item. */
    public Map<ItemKey, Long> delta(HolderId holder, Map<ItemKey, Long> actual) {
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
        if (mergeAutomation && !pending.isEmpty() && event instanceof LedgerEvent.Transferred next) {
            val last = pending.getLast();
            if (last.cause().equals(cause)
                    && last.event() instanceof LedgerEvent.Transferred previous
                    && repeats(previous, next)) {
                val merged = new LedgerEvent.Transferred(
                        next.holder(), next.to(), next.item(), previous.amount() + next.amount(), TaintVector.EMPTY);
                pending.set(pending.size() - 1, new Envelope(last.seq(), last.time(), cause, merged));
                return;
            }
        }
        pending.add(new Envelope(++seq, clock.getAsLong(), cause, event));
    }

    /**
     * Back-to-back automation moves of one item between the same two holders (a hopper ticking) are journaled as one
     * event. Replay is unaffected: each move was capped by the tracked count, so their sum is too.
     */
    private static boolean repeats(LedgerEvent.Transferred previous, LedgerEvent.Transferred next) {
        return previous.holder().equals(next.holder())
                && previous.to().equals(next.to())
                && previous.item().equals(next.item())
                && !next.holder().isPlayer()
                && !next.to().isPlayer();
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

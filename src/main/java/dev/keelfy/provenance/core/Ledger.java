package dev.keelfy.provenance.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.NotNull;

import lombok.Getter;
import lombok.val;

/**
 * Account-model ledger: holder × item → pool. Not thread-safe; owned by the server main thread.
 * Every operation conserves counts except {@link #mint} and {@link #burn}; shortfalls are minted as {@link OriginId#UNKNOWN}.
 * 
 * @author keelfy
 */
public final class Ledger {

    @Getter 
    private final @NotNull FlowGraph flows = new FlowGraph();
    private final @NotNull Map<HolderId, Map<ItemKey, Pool>> holders = new HashMap<>();

    public Pool pool(@NotNull HolderId holder, @NotNull ItemKey item) {
        return holders.getOrDefault(holder, Map.of()).get(item);
    }

    public long getCount(@NotNull HolderId holder, @NotNull ItemKey item) {
        val pool = pool(holder, item);
        return pool != null ? pool.getCount() : 0L;
    }

    public @NotNull Map<ItemKey, Pool> getPools(@NotNull HolderId holder) {
        return Collections.unmodifiableMap(holders.getOrDefault(holder, Map.of()));
    }

    public @NotNull Set<HolderId> getHolders() {
        return Collections.unmodifiableSet(holders.keySet());
    }

    public void mint(@NotNull HolderId holder, @NotNull ItemKey item, long amount, @NotNull OriginId origin) {
        if (amount <= 0) {
            return;
        }
        val originVector = TaintVector.of(origin);
        val custody = holder.isPlayer() ? TaintVector.of(holder.asPlayerOrigin()) : originVector;
        poolFor(holder, item).add(amount, originVector, custody);
    }

    /** @return amount actually removed (capped at the tracked count) */
    public long burn(@NotNull HolderId holder, @NotNull ItemKey item, long amount) {
        val pool = pool(holder, item);
        if (pool == null || amount <= 0) {
            return 0;
        }

        val removed = Math.min(amount, pool.getCount());
        pool.remove(removed);
        dropIfEmpty(holder, item, pool);
        return removed;
    }

    /** Removes everything a holder has, e.g. a despawned item entity or a destroyed container. */
    public void clear(@NotNull HolderId holder) {
        holders.remove(holder);
    }

    /**
     * Moves {@code amount} items with haircut semantics. Player-to-player custody changes are recorded as flows.
     * If {@code from} tracks fewer items than moved, the shortfall arrives in {@code to} as {@link OriginId#UNKNOWN}.
     */
    public void transfer(@NotNull HolderId from, @NotNull HolderId to, @NotNull ItemKey item, long amount) {
        if (amount <= 0 || from.equals(to)) {
            return;
        }

        val source = pool(from, item);
        val moved = source == null ? 0 : Math.min(amount, source.getCount());

        if (moved > 0 && source != null) {
            val custody = from.isPlayer() ? TaintVector.of(from.asPlayerOrigin()) : source.getCustody();
            deposit(to, item, moved, source.getOrigin(), custody);
            source.remove(moved);
            dropIfEmpty(from, item, source);
        }
        mint(to, item, amount - moved, OriginId.UNKNOWN);
    }

    /**
     * Crafting/smelting inside one holder: inputs are consumed, outputs inherit the count-weighted mix of input vectors.
     * Inputs the ledger does not know about contribute {@link OriginId#UNKNOWN}.
     */
    public void convert(@NotNull HolderId holder, @NotNull Map<ItemKey, Long> inputs, @NotNull Map<ItemKey, Long> outputs) {
        val origin = TaintVector.builder();
        val custody = TaintVector.builder();

        inputs.forEach((item, amount) -> {
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

        for (val output : outputs.entrySet()) {
            if (output.getValue() > 0) {
                poolFor(holder, output.getKey()).add(output.getValue(), outOrigin, outCustody);
            }
        }
    }

    /** Aligns tracked counts with reality: missing items are burned, unexplained items minted as {@link OriginId#UNKNOWN}. */
    public void reconcile(@NotNull HolderId holder, @NotNull Map<ItemKey, Long> actual) {
        val items = new HashSet<ItemKey>(actual.keySet());
        items.addAll(holders.getOrDefault(holder, Map.of()).keySet());

        for (val item : items) {
            val diff = actual.getOrDefault(item, 0L) - getCount(holder, item);
            if (diff > 0) {
                mint(holder, item, diff, OriginId.UNKNOWN);
            } else if (diff < 0) {
                burn(holder, item, -diff);
            }
        }
    }

    private void deposit(@NotNull HolderId to, @NotNull ItemKey item, long amount, @NotNull TaintVector origin, @NotNull TaintVector custody) {
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

    private Pool poolFor(@NotNull HolderId holder, @NotNull ItemKey item) {
        return holders.computeIfAbsent(holder, k -> new HashMap<>())
                .computeIfAbsent(item, k -> new Pool(0, TaintVector.EMPTY, TaintVector.EMPTY));
    }

    private void dropIfEmpty(@NotNull HolderId holder, @NotNull ItemKey item, @NotNull Pool pool) {
        if (pool.getCount() > 0) {
            return;
        }

        val pools = holders.get(holder);
        pools.remove(item);
        if (pools.isEmpty()) {
            holders.remove(holder);
        }
    }
}

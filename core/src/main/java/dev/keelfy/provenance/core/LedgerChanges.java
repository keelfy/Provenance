package dev.keelfy.provenance.core;

import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

/**
 * Immutable snapshot of what changed in a {@link Ledger} since the last drain, safe to hand to another thread.
 * A holder mapped to an empty list no longer holds anything and must be deleted from storage.
 *
 * @author keelfy
 */
public record LedgerChanges(@NotNull Map<HolderId, List<PoolRow>> holders, @NotNull List<FlowRow> flows) {

    public record PoolRow(@NotNull ItemKey item, long count, @NotNull TaintVector origin, @NotNull TaintVector custody) {
    }

    public record FlowRow(@NotNull OriginId from, @NotNull OriginId to, @NotNull ItemKey item, double amount) {
    }

    public boolean isEmpty() {
        return holders.isEmpty() && flows.isEmpty();
    }
}

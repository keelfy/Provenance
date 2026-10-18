package dev.keelfy.provenance.core;

import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of what changed in a {@link Ledger} since the last drain, safe to hand to another thread. A holder
 * mapped to an empty list no longer holds anything and must be deleted from storage. {@code events} are the journal
 * entries of the same period, {@code lastSeq} the sequence number the pool rows reflect.
 *
 * @author keelfy
 */
public record LedgerChanges(
        Map<HolderId, List<PoolRow>> holders, List<FlowRow> flows, List<Envelope> events, long lastSeq) {

    public record PoolRow(ItemKey item, long count, TaintVector origin, TaintVector custody) {}

    public record FlowRow(OriginId from, OriginId to, ItemKey item, double amount) {}

    public boolean isEmpty() {
        return holders.isEmpty() && flows.isEmpty() && events.isEmpty();
    }
}

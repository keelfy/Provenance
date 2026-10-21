package dev.keelfy.provenance.core;

import dev.keelfy.provenance.core.event.Envelope;
import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.HolderId;
import dev.keelfy.provenance.core.model.ItemKey;
import dev.keelfy.provenance.core.model.OriginId;
import dev.keelfy.provenance.core.taint.TaintVector;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of what changed in a {@link Ledger} since the last drain, safe to hand to another thread. A holder
 * mapped to an empty list no longer holds anything and must be deleted from storage. {@code events} are the journal
 * entries of the same period, {@code lastSeq} the sequence number the pool rows reflect. {@code seen} are the holders
 * looked into for the first time (see {@link Ledger#reconcile}).
 *
 * @author keelfy
 */
public record LedgerChanges(
        Map<Holder, List<PoolRow>> holders,
        List<FlowRow> flows,
        List<Envelope> events,
        long lastSeq,
        List<HolderId> seen) {

    public record PoolRow(ItemKey item, long count, TaintVector origin, TaintVector custody) {}

    public record FlowRow(OriginId from, OriginId to, ItemKey item, double amount) {}

    public boolean isEmpty() {
        return holders.isEmpty() && flows.isEmpty() && events.isEmpty() && seen.isEmpty();
    }
}

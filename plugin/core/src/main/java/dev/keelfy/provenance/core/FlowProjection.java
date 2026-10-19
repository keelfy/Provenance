package dev.keelfy.provenance.core;

import dev.keelfy.provenance.core.event.LedgerEvent;
import lombok.RequiredArgsConstructor;
import lombok.val;

/**
 * Records who handed items to whom whenever a transfer ends at a player. Reads the pools to learn the custody and the
 * amount actually moved, so it must see each event before {@link PoolProjection} does.
 *
 * @author keelfy
 */
@RequiredArgsConstructor
final class FlowProjection implements Projection {

    private final FlowGraph flows;
    private final PoolProjection pools;

    @Override
    public void apply(LedgerEvent event) {
        if (!(event instanceof LedgerEvent.Transferred e) || !e.to().isPlayer()) {
            return;
        }

        val source = pools.pool(e.holder(), e.item());
        if (source == null) {
            return;
        }

        val moved = pools.movable(e.holder(), e.item(), e.amount());
        if (moved <= 0) {
            return;
        }

        val receiver = e.to().asPlayerOrigin();
        pools.custodyOf(e.holder(), e.item()).shares().forEach((custodian, share) -> {
            if (custodian.isPlayer()) {
                flows.record(custodian, receiver, e.item(), share * moved);
            }
        });
    }
}

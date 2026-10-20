package dev.keelfy.provenance.core.event;

import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.ItemKey;
import dev.keelfy.provenance.core.model.OriginId;
import dev.keelfy.provenance.core.taint.TaintVector;
import java.util.Map;

/**
 * A fact about the ledger. {@code LedgerState.apply} is the only way state changes, both live and on replay, so the
 * event stream alone reproduces the state.
 *
 * @author keelfy
 */
public sealed interface LedgerEvent {

    /** The holder an event is mainly about: the source of a transfer, otherwise the holder itself. */
    Holder holder();

    record Minted(Holder holder, ItemKey item, long amount, OriginId origin) implements LedgerEvent {}

    /**
     * {@code amount} is what the source really held. {@code custody} is who held it, filled only when the receiver is a
     * player (it feeds the flow graph and tells a moderator whose items were taken); replay recomputes it from state.
     */
    record Transferred(Holder holder, Holder to, ItemKey item, long amount, TaintVector custody)
            implements LedgerEvent {}

    /** {@code amount} is what was actually removed. */
    record Burned(Holder holder, ItemKey item, long amount) implements LedgerEvent {}

    /** Both maps are ordered by item key so that replay is deterministic. */
    record Converted(Holder holder, Map<ItemKey, Long> inputs, Map<ItemKey, Long> outputs) implements LedgerEvent {}

    record Cleared(Holder holder) implements LedgerEvent {}

    /**
     * A player's inventory arrived from another server together with the pools it was tracked as there: everything the
     * player held here is replaced by {@code pools}. {@code from} is that server. Ordered by item key like
     * {@link Converted}.
     */
    record Synced(Holder holder, Holder from, Map<ItemKey, PoolState> pools) implements LedgerEvent {}
}

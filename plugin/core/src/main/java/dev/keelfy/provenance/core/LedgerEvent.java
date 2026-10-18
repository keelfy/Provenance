package dev.keelfy.provenance.core;

import java.util.Map;

/**
 * A fact about the ledger. {@link LedgerState#apply} is the only way state changes, both live and on replay, so the
 * event stream alone reproduces the state.
 *
 * @author keelfy
 */
public sealed interface LedgerEvent {

    /** The holder an event is mainly about: the source of a transfer, otherwise the holder itself. */
    HolderId holder();

    record Minted(HolderId holder, ItemKey item, long amount, OriginId origin) implements LedgerEvent {}

    /**
     * {@code amount} is what the source really held. {@code custody} is who held it, filled only when the receiver is a
     * player (it feeds the flow graph and tells a moderator whose items were taken); replay recomputes it from state.
     */
    record Transferred(HolderId holder, HolderId to, ItemKey item, long amount, TaintVector custody)
            implements LedgerEvent {}

    /** {@code amount} is what was actually removed. */
    record Burned(HolderId holder, ItemKey item, long amount) implements LedgerEvent {}

    /** Both maps are ordered by item key so that replay is deterministic. */
    record Converted(HolderId holder, Map<ItemKey, Long> inputs, Map<ItemKey, Long> outputs) implements LedgerEvent {}

    record Cleared(HolderId holder) implements LedgerEvent {}
}

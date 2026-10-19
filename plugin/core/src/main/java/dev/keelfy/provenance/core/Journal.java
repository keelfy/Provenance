package dev.keelfy.provenance.core;

import dev.keelfy.provenance.core.event.Envelope;
import dev.keelfy.provenance.core.event.LedgerEvent;
import dev.keelfy.provenance.core.model.Cause;
import dev.keelfy.provenance.core.taint.TaintVector;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import lombok.Getter;
import lombok.val;

/**
 * The append-only record of events since the last flush: owns the sequence number, the clock and the coalescing of
 * automation moves. Knows nothing about balances. Not thread-safe.
 *
 * @author keelfy
 */
final class Journal {

    private final LongSupplier clock;
    private final boolean mergeAutomation;
    private final List<Envelope> pending = new ArrayList<>();

    /** Sequence number of the newest event, journaled or replayed. */
    @Getter
    private long seq;

    Journal(LongSupplier clock, boolean mergeAutomation) {
        this.clock = clock;
        this.mergeAutomation = mergeAutomation;
    }

    void append(Cause cause, LedgerEvent event) {
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

    /** Hands over everything appended since the previous call; the journal starts over, the sequence does not. */
    List<Envelope> drain() {
        val events = List.copyOf(pending);
        pending.clear();
        return events;
    }

    /** Continues numbering after {@code lastSeq}, e.g. the head of a restored snapshot or a replayed event. */
    void resumeAfter(long lastSeq) {
        seq = lastSeq;
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
}

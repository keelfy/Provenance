package dev.keelfy.provenance.core.event;

import dev.keelfy.provenance.core.model.Cause;
import dev.keelfy.provenance.core.model.Chunk;
import org.jspecify.annotations.Nullable;

/**
 * A {@link LedgerEvent} in the journal: gapless increasing {@code seq}, wall-clock {@code time} in millis, and the
 * cause. A stored event may stand for many similar ones that happened in a time bucket and a {@code chunk}: then
 * {@code time} is the first of them and {@code until} the last, and the amounts are their sum. {@code server} names the
 * server that journaled it when that is not this one (a store shared by several servers), otherwise {@code null}.
 *
 * @author keelfy
 */
public record Envelope(
        long seq,
        long time,
        long until,
        Cause cause,
        LedgerEvent event,
        @Nullable Chunk chunk,
        @Nullable String server) {

    public Envelope(long seq, long time, long until, Cause cause, LedgerEvent event, @Nullable Chunk chunk) {
        this(seq, time, until, cause, event, chunk, null);
    }

    public Envelope(long seq, long time, Cause cause, LedgerEvent event) {
        this(seq, time, time, cause, event, null, null);
    }
}

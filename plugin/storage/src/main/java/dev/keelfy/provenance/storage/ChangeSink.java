package dev.keelfy.provenance.storage;

import dev.keelfy.provenance.core.LedgerChanges;
import dev.keelfy.provenance.core.event.Envelope;
import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.ItemKey;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Where {@link WriteBehind} sends batches, and the journal it reads history from.
 *
 * @author keelfy
 */
public interface ChangeSink extends AutoCloseable {

    void write(LedgerChanges changes) throws Exception;

    /** Newest-first events involving {@code holder}, optionally only those touching {@code item}. */
    List<Envelope> history(Holder holder, @Nullable ItemKey item, int limit) throws Exception;

    /**
     * Breadth-first walk over holders linked by events: depth 1 is the events of {@code root}, depth 2 adds the events
     * of every holder it exchanged items with, and so on. Only events at or after {@code since} (millis) and, if given,
     * touching {@code item} are followed. At most {@code limit} events are returned (the newest);
     * {@link Trace#truncated()} tells if more existed.
     */
    Trace trace(Holder root, @Nullable ItemKey item, long since, int depth, int limit) throws Exception;

    /** Drops journal entries that outlived their retention as of {@code now} (millis). */
    void prune(long now) throws Exception;

    /** Adds up the similar events of every time bucket that has ended as of {@code now} (millis). */
    void compact(long now) throws Exception;
}

package dev.keelfy.provenance.storage;

import dev.keelfy.provenance.core.Envelope;
import dev.keelfy.provenance.core.HolderId;
import dev.keelfy.provenance.core.ItemKey;
import dev.keelfy.provenance.core.LedgerChanges;
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
    List<Envelope> history(HolderId holder, @Nullable ItemKey item, int limit) throws Exception;

    /** Drops journal entries that outlived their retention as of {@code now} (millis). */
    void prune(long now) throws Exception;
}

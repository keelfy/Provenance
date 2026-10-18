package dev.keelfy.provenance.storage;

import org.jetbrains.annotations.NotNull;

import dev.keelfy.provenance.core.LedgerChanges;

/**
 * Where {@link WriteBehind} sends batches.
 *
 * @author keelfy
 */
public interface ChangeSink extends AutoCloseable {

    void write(@NotNull LedgerChanges changes) throws Exception;
}

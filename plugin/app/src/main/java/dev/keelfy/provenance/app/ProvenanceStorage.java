package dev.keelfy.provenance.app;

import dev.keelfy.provenance.app.port.Log;
import dev.keelfy.provenance.app.port.Scheduler;
import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.core.event.Envelope;
import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.ItemKey;
import dev.keelfy.provenance.storage.SqliteStore;
import dev.keelfy.provenance.storage.Trace;
import dev.keelfy.provenance.storage.WriteBehind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.jspecify.annotations.Nullable;

/** @author keelfy */
@RequiredArgsConstructor
public class ProvenanceStorage {

    /** Pruning waits a second after startup so it does not compete with the load. */
    private static final long PRUNE_DELAY_TICKS = 20;

    private static final long TICKS_PER_SECOND = 20;

    private final Path dataDirectory;
    private final Log log;
    private final Scheduler scheduler;
    private final Ledger ledger;
    private final Settings settings;

    private WriteBehind writer;

    public boolean open() {
        try {
            Files.createDirectories(dataDirectory);
            val store =
                    new SqliteStore(dataDirectory.resolve("provenance.db"), settings.retention(), settings.bucket());
            store.load(ledger);
            writer = new WriteBehind(
                    store, e -> log.severe("Storage write failed, will retry: " + e), settings.shutdownTimeout());
            log.info("Loaded " + ledger.getHolders().size() + " holders");
            return true;
        } catch (SQLException | IOException e) {
            log.severe("Storage unavailable, disabling: " + e);
            return false;
        }
    }

    public void runScheduler() {
        scheduler.runEvery(settings.flushTicks(), settings.flushTicks(), this::flush);
        scheduler.runEvery(PRUNE_DELAY_TICKS, settings.pruneTicks(), () -> writer.prune(System.currentTimeMillis()));
        val bucketTicks = settings.bucket().toSeconds() * TICKS_PER_SECOND;
        scheduler.runEvery(PRUNE_DELAY_TICKS, bucketTicks, () -> writer.compact(System.currentTimeMillis()));
    }

    /**
     * Newest-first journal of {@code holder}; completes on the writer thread after everything recorded so far is
     * stored.
     */
    public CompletableFuture<List<Envelope>> history(Holder holder, @Nullable ItemKey item, int limit) {
        flush();
        return writer.history(holder, item, limit);
    }

    /** Events within {@code depth} holders of {@code root}; see {@link WriteBehind#trace}. */
    public CompletableFuture<Trace> trace(Holder root, @Nullable ItemKey item, int depth, int limit) {
        flush();
        return writer.trace(root, item, 0, depth, limit);
    }

    public void flush() {
        writer.submit(ledger.drainChanges());
    }

    public void close() {
        flush();

        try {
            writer.close();
        } catch (Exception e) {
            log.severe("Could not close storage: " + e);
        }
    }
}

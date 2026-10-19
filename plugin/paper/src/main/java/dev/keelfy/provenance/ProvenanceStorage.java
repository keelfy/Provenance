package dev.keelfy.provenance;

import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.core.event.Envelope;
import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.ItemKey;
import dev.keelfy.provenance.storage.SqliteStore;
import dev.keelfy.provenance.storage.Trace;
import dev.keelfy.provenance.storage.WriteBehind;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.bukkit.Server;
import org.jspecify.annotations.Nullable;

/** @author keelfy */
@RequiredArgsConstructor
public class ProvenanceStorage {

    /** Pruning waits a second after startup so it does not compete with the load. */
    private static final long PRUNE_DELAY_TICKS = 20;

    private static final long TICKS_PER_SECOND = 20;

    private final ProvenancePlugin plugin;
    private final Logger logger;
    private final Server server;
    private final Ledger ledger;
    private final Settings settings;

    private WriteBehind writer;

    boolean open() {
        try {
            Files.createDirectories(plugin.getDataFolder().toPath());
            val store = new SqliteStore(
                    plugin.getDataFolder().toPath().resolve("provenance.db"), settings.retention(), settings.bucket());
            store.load(ledger);
            writer = new WriteBehind(
                    store, e -> logger.severe("Storage write failed, will retry: " + e), settings.shutdownTimeout());
            logger.info("Loaded " + ledger.getHolders().size() + " holders");
            return true;
        } catch (SQLException | IOException e) {
            logger.severe("Storage unavailable, disabling: " + e);
            return false;
        }
    }

    void runScheduler() {
        server.getScheduler().runTaskTimer(plugin, this::flush, settings.flushTicks(), settings.flushTicks());
        server.getScheduler()
                .runTaskTimer(
                        plugin,
                        () -> writer.prune(System.currentTimeMillis()),
                        PRUNE_DELAY_TICKS,
                        settings.pruneTicks());
        val bucketTicks = settings.bucket().toSeconds() * TICKS_PER_SECOND;
        server.getScheduler()
                .runTaskTimer(plugin, () -> writer.compact(System.currentTimeMillis()), PRUNE_DELAY_TICKS, bucketTicks);
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

    void flush() {
        writer.submit(ledger.drainChanges());
    }

    void close() {
        flush();

        try {
            writer.close();
        } catch (Exception e) {
            logger.severe("Could not close storage: " + e);
        }
    }
}

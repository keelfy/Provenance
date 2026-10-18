package dev.keelfy.provenance;

import dev.keelfy.provenance.core.Envelope;
import dev.keelfy.provenance.core.HolderId;
import dev.keelfy.provenance.core.ItemKey;
import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.storage.SqliteStore;
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

    private final ProvenancePlugin plugin;
    private final Logger logger;
    private final Server server;
    private final Ledger ledger;
    private final Settings settings;

    private WriteBehind writer;

    boolean open() {
        try {
            Files.createDirectories(plugin.getDataFolder().toPath());
            val store = new SqliteStore(plugin.getDataFolder().toPath().resolve("provenance.db"), settings.retention());
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
    }

    /**
     * Newest-first journal of {@code holder}; completes on the writer thread after everything recorded so far is
     * stored.
     */
    public CompletableFuture<List<Envelope>> history(HolderId holder, @Nullable ItemKey item, int limit) {
        flush();
        return writer.history(holder, item, limit);
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

package dev.keelfy.provenance;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.logging.Logger;

import org.bukkit.Server;

import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.storage.SqliteStore;
import dev.keelfy.provenance.storage.WriteBehind;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.val;

/**
 * @author keelfy
 */
@RequiredArgsConstructor 
public class ProvenanceStorage {

    /** How often changed holders are snapshotted and handed to the writer thread. */
    private static final long FLUSH_PERIOD_TICKS = 600;

    private final @NonNull ProvenancePlugin plugin;
    private final @NonNull Logger logger;
    private final @NonNull Server server;
    private final @NonNull Ledger ledger;

    private WriteBehind writer;

    boolean open() {
        try {
            Files.createDirectories(plugin.getDataFolder().toPath());
            val store = new SqliteStore(plugin.getDataFolder().toPath().resolve("provenance.db"));
            store.load(ledger);
            writer = new WriteBehind(store, e -> logger.severe("Storage write failed, will retry: " + e));
            logger.info("Loaded " + ledger.getHolders().size() + " holders");
            return true;
        } catch (SQLException | IOException e) {
            logger.severe("Storage unavailable, disabling: " + e);
            return false;
        }
    }

    void runScheduler() {
        server.getScheduler().runTaskTimer(plugin, this::flush, FLUSH_PERIOD_TICKS, FLUSH_PERIOD_TICKS);
    }

    void flush() {
        writer.submit(ledger.drainChanges());
    }

    void close() {
        if (writer == null) {
            return;
        }

        flush();
        
        try {
            writer.close();
        } catch (Exception e) {
            logger.severe("Could not close storage: " + e);
        }
    }
}

package dev.keelfy.provenance.storage;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.jetbrains.annotations.NotNull;

import dev.keelfy.provenance.core.HolderId;
import dev.keelfy.provenance.core.ItemKey;
import dev.keelfy.provenance.core.Kind;
import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.core.LedgerChanges;
import dev.keelfy.provenance.core.OriginId;
import lombok.val;

/**
 * SQLite persistence for a {@link Ledger}: one row per pool and per flow edge. Single connection, not thread-safe;
 * {@link WriteBehind} serializes access after {@link #load}.
 *
 * @author keelfy
 */
public final class SqliteStore implements ChangeSink {

    private static final int SCHEMA_VERSION = 1;

    private final @NotNull Connection connection;

    public SqliteStore(@NotNull Path file) throws SQLException {
        this("jdbc:sqlite:" + file.toAbsolutePath());
    }

    public SqliteStore(@NotNull String url) throws SQLException {
        connection = DriverManager.getConnection(url);
        try (val statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS pool (
                        holder_kind TEXT NOT NULL, holder_id TEXT NOT NULL, item TEXT NOT NULL,
                        count INTEGER NOT NULL, origin TEXT NOT NULL, custody TEXT NOT NULL,
                        PRIMARY KEY (holder_kind, holder_id, item)
                    ) WITHOUT ROWID""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS flow (
                        from_origin TEXT NOT NULL, to_origin TEXT NOT NULL, item TEXT NOT NULL,
                        amount REAL NOT NULL,
                        PRIMARY KEY (from_origin, to_origin, item)
                    ) WITHOUT ROWID""");
            statement.execute("PRAGMA user_version=" + SCHEMA_VERSION);
        }
    }

    /** Fills an empty ledger with the stored state; the ledger stays clean (nothing to write back). */
    public void load(@NotNull Ledger ledger) throws SQLException {
        try (val statement = connection.createStatement()) {
            try (val rows = statement.executeQuery("SELECT holder_kind, holder_id, item, count, origin, custody FROM pool")) {
                while (rows.next()) {
                    val holder = new HolderId(Kind.valueOf(rows.getString(1)), rows.getString(2));
                    ledger.restore(holder, new LedgerChanges.PoolRow(new ItemKey(rows.getString(3)), rows.getLong(4),
                            VectorCodec.decode(rows.getString(5)), VectorCodec.decode(rows.getString(6))));
                }
            }
            try (val rows = statement.executeQuery("SELECT from_origin, to_origin, item, amount FROM flow")) {
                while (rows.next()) {
                    ledger.restore(new LedgerChanges.FlowRow(new OriginId(rows.getString(1)), new OriginId(rows.getString(2)),
                            new ItemKey(rows.getString(3)), rows.getDouble(4)));
                }
            }
        }
    }

    /** Applies one batch atomically: changed holders are replaced wholesale, flow totals overwritten. */
    @Override
    public void write(@NotNull LedgerChanges changes) throws SQLException {
        connection.setAutoCommit(false);
        try (val delete = connection.prepareStatement("DELETE FROM pool WHERE holder_kind = ? AND holder_id = ?");
             val insert = connection.prepareStatement("INSERT INTO pool VALUES (?, ?, ?, ?, ?, ?)");
             val flow = connection.prepareStatement("INSERT OR REPLACE INTO flow VALUES (?, ?, ?, ?)")) {

            for (val entry : changes.holders().entrySet()) {
                val holder = entry.getKey();
                delete.setString(1, holder.kind().name());
                delete.setString(2, holder.id());
                delete.addBatch();

                for (val row : entry.getValue()) {
                    insert.setString(1, holder.kind().name());
                    insert.setString(2, holder.id());
                    insert.setString(3, row.item().key());
                    insert.setLong(4, row.count());
                    insert.setString(5, VectorCodec.encode(row.origin()));
                    insert.setString(6, VectorCodec.encode(row.custody()));
                    insert.addBatch();
                }
            }
            for (val row : changes.flows()) {
                flow.setString(1, row.from().key());
                flow.setString(2, row.to().key());
                flow.setString(3, row.item().key());
                flow.setDouble(4, row.amount());
                flow.addBatch();
            }

            delete.executeBatch();
            insert.executeBatch();
            flow.executeBatch();
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}

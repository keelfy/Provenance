package dev.keelfy.provenance.storage;

import dev.keelfy.provenance.core.Envelope;
import dev.keelfy.provenance.core.HolderId;
import dev.keelfy.provenance.core.ItemKey;
import dev.keelfy.provenance.core.Kind;
import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.core.LedgerChanges;
import dev.keelfy.provenance.core.OriginId;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import lombok.val;
import org.jspecify.annotations.Nullable;

/**
 * SQLite persistence for a {@link Ledger}: one row per pool and per flow edge (the snapshot) plus the event journal. A
 * batch writes snapshot rows, events and {@code last_seq} in one transaction, so on load the journal tail after
 * {@code last_seq} is exactly what the snapshot lacks. Single connection, not thread-safe; {@link WriteBehind}
 * serializes access after {@link #load}.
 *
 * @author keelfy
 */
public final class SqliteStore implements ChangeSink {

    /** No migration: a database of another version is dropped (pre-release). */
    private static final int SCHEMA_VERSION = 2;

    private static final String LAST_SEQ = "last_seq";
    private static final String EVENT_COLUMNS =
            "seq, time, cause, type, holder, other, item, amount, origin, custody, detail, durable";

    private final Retention retention;
    private final Connection connection;

    public SqliteStore(Path file, Retention retention) throws SQLException {
        this("jdbc:sqlite:" + file.toAbsolutePath(), retention);
    }

    public SqliteStore(String url, Retention retention) throws SQLException {
        this.retention = retention;
        connection = DriverManager.getConnection(url);
        try (val statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            try (val version = statement.executeQuery("PRAGMA user_version")) {
                if (version.getInt(1) != SCHEMA_VERSION) {
                    for (val table : List.of("pool", "flow", "event", "meta")) {
                        statement.execute("DROP TABLE IF EXISTS " + table);
                    }
                }
            }
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
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS meta (
                        key TEXT PRIMARY KEY, value INTEGER NOT NULL
                    ) WITHOUT ROWID""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS event (
                        seq INTEGER PRIMARY KEY, time INTEGER NOT NULL, cause TEXT NOT NULL, type TEXT NOT NULL,
                        holder TEXT NOT NULL, other TEXT, item TEXT, amount INTEGER NOT NULL,
                        origin TEXT, custody TEXT, detail TEXT, durable INTEGER NOT NULL
                    )""");
            statement.execute("CREATE INDEX IF NOT EXISTS event_holder ON event (holder, seq)");
            statement.execute("CREATE INDEX IF NOT EXISTS event_other ON event (other, seq)");
            statement.execute("PRAGMA user_version=" + SCHEMA_VERSION);
        }
    }

    /**
     * Fills an empty ledger with the snapshot, then replays journal events newer than it. Replayed holders are dirty,
     * so the next flush folds the tail into the snapshot; the journal itself needs no rewrite.
     */
    public void load(Ledger ledger) throws SQLException {
        try (val statement = connection.createStatement()) {
            try (val rows =
                    statement.executeQuery("SELECT holder_kind, holder_id, item, count, origin, custody FROM pool")) {
                while (rows.next()) {
                    val holder = new HolderId(Kind.valueOf(rows.getString(1)), rows.getString(2));
                    ledger.restore(
                            holder,
                            new LedgerChanges.PoolRow(
                                    new ItemKey(rows.getString(3)),
                                    rows.getLong(4),
                                    VectorCodec.decode(rows.getString(5)),
                                    VectorCodec.decode(rows.getString(6))));
                }
            }
            try (val rows = statement.executeQuery("SELECT from_origin, to_origin, item, amount FROM flow")) {
                while (rows.next()) {
                    ledger.restore(new LedgerChanges.FlowRow(
                            new OriginId(rows.getString(1)),
                            new OriginId(rows.getString(2)),
                            new ItemKey(rows.getString(3)),
                            rows.getDouble(4)));
                }
            }

            long lastSeq = 0;
            try (val rows = statement.executeQuery("SELECT value FROM meta WHERE key = '" + LAST_SEQ + "'")) {
                if (rows.next()) {
                    lastSeq = rows.getLong(1);
                }
            }
            ledger.restoreSeq(lastSeq);

            try (val rows = statement.executeQuery(
                    "SELECT " + EVENT_COLUMNS + " FROM event WHERE seq > " + lastSeq + " ORDER BY seq")) {
                while (rows.next()) {
                    ledger.replay(read(rows).toEnvelope());
                }
            }
        }
    }

    /** Applies one batch atomically: changed holders are replaced wholesale, flow totals overwritten. */
    @Override
    public void write(LedgerChanges changes) throws SQLException {
        connection.setAutoCommit(false);
        try (val delete = connection.prepareStatement("DELETE FROM pool WHERE holder_kind = ? AND holder_id = ?");
                val insert = connection.prepareStatement("INSERT INTO pool VALUES (?, ?, ?, ?, ?, ?)");
                val flow = connection.prepareStatement("INSERT OR REPLACE INTO flow VALUES (?, ?, ?, ?)");
                val event = connection.prepareStatement("INSERT OR IGNORE INTO event (" + EVENT_COLUMNS
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                val meta = connection.prepareStatement("INSERT OR REPLACE INTO meta VALUES ('" + LAST_SEQ + "', ?)")) {

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

            for (val envelope : changes.events()) {
                val row = EventRow.of(envelope);
                event.setLong(1, row.seq());
                event.setLong(2, row.time());
                event.setString(3, row.cause());
                event.setString(4, row.type());
                event.setString(5, row.holder());
                event.setString(6, row.other());
                event.setString(7, row.item());
                event.setLong(8, row.amount());
                event.setString(9, row.origin());
                event.setString(10, row.custody());
                event.setString(11, row.detail());
                event.setInt(12, row.durable() ? 1 : 0);
                event.addBatch();
            }
            meta.setLong(1, changes.lastSeq());

            delete.executeBatch();
            insert.executeBatch();
            flow.executeBatch();
            event.executeBatch();
            meta.executeUpdate();
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    @Override
    public List<Envelope> history(HolderId holder, @Nullable ItemKey item, int limit) throws SQLException {
        val sql = "SELECT " + EVENT_COLUMNS + " FROM event WHERE (holder = ?1 OR other = ?1)"
                + (item == null ? "" : " AND (item = ?2 OR instr(detail, ?3) > 0)")
                + " ORDER BY seq DESC LIMIT ?4";
        val events = new ArrayList<Envelope>();

        try (val query = connection.prepareStatement(sql)) {
            query.setString(1, EventRow.encode(holder));
            if (item != null) {
                query.setString(2, item.key());
                query.setString(3, "|" + item.key() + "=");
            }
            query.setInt(4, limit);
            try (val rows = query.executeQuery()) {
                while (rows.next()) {
                    events.add(read(rows).toEnvelope());
                }
            }
        }
        return events;
    }

    @Override
    public void prune(long now) throws SQLException {
        try (val delete =
                connection.prepareStatement("DELETE FROM event WHERE time < CASE durable WHEN 1 THEN ?1 ELSE ?2 END")) {
            delete.setLong(1, now - retention.durable().toMillis());
            delete.setLong(2, now - retention.automation().toMillis());
            delete.executeUpdate();
        }
    }

    private static EventRow read(ResultSet rows) throws SQLException {
        return new EventRow(
                rows.getLong(1),
                rows.getLong(2),
                rows.getString(3),
                rows.getString(4),
                rows.getString(5),
                rows.getString(6),
                rows.getString(7),
                rows.getLong(8),
                rows.getString(9),
                rows.getString(10),
                rows.getString(11),
                rows.getInt(12) == 1);
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}

package dev.keelfy.provenance.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.core.LedgerChanges;
import dev.keelfy.provenance.core.event.Envelope;
import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.HolderMeta;
import dev.keelfy.provenance.core.model.ItemKey;
import dev.keelfy.provenance.core.model.Kind;
import dev.keelfy.provenance.core.model.OriginId;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import lombok.val;
import org.jspecify.annotations.Nullable;

/**
 * SQL persistence (SQLite, MySQL/MariaDB or PostgreSQL, see {@link Dialect}) for a {@link Ledger}: one row per pool and
 * per flow edge (the snapshot) plus the event journal. A batch writes snapshot rows, events and {@code last_seq} in one
 * transaction, so on load the journal tail after {@code last_seq} is exactly what the snapshot lacks. Connections come
 * from a small HikariCP pool, so a connection the server dropped is replaced instead of failing every later write.
 * Callers still do not overlap: {@link WriteBehind} serializes access after {@link #load}.
 *
 * @author keelfy
 */
public final class SqlStore implements ChangeSink {

    /** No migration: a database of another version is dropped (pre-release). */
    private static final int SCHEMA_VERSION = 6;

    private static final String LAST_SEQ = "last_seq";
    private static final String COMPACTED = "compacted";
    /** How long after a bucket ends its last stragglers (items still on the ground, queued writes) may arrive. */
    private static final long SETTLE_MILLIS = Duration.ofMinutes(10).toMillis();
    /** A database that is down fails a write after this long instead of hanging the writer thread. */
    private static final long CONNECT_TIMEOUT_MILLIS = Duration.ofSeconds(10).toMillis();
    /** Access is serial; the spare connection only lets the pool replace a broken one without waiting. */
    private static final int REMOTE_POOL_SIZE = 2;

    private static final String ITEM_ENTITY = "minecraft:item";
    private static final List<String> EVENT_COLUMN_LIST = List.of(
            "seq",
            "time",
            "cause",
            "type",
            "holder",
            "other",
            "item",
            "amount",
            "origin",
            "custody",
            "detail",
            "durable",
            "holder_type",
            "holder_label",
            "other_type",
            "other_label",
            "until",
            "chunk");
    private static final String EVENT_COLUMNS = String.join(", ", EVENT_COLUMN_LIST);

    private final Dialect dialect;
    private final Retention retention;
    private final long bucketMillis;
    private final HikariDataSource source;
    private final String insertEvent;
    private final String upsertFlow;
    private final String upsertMeta;

    /**
     * @param dataDirectory where a SQLite file lives
     * @param bucket the span of time whose similar events {@link #compact} adds up
     */
    public SqlStore(Database database, Path dataDirectory, Retention retention, Duration bucket) throws SQLException {
        dialect = database.dialect();
        this.retention = retention;
        this.bucketMillis = bucket.toMillis();
        insertEvent = dialect.insertIgnore("event", EVENT_COLUMN_LIST, "seq");
        upsertFlow = dialect.upsert("flow", List.of("from_origin", "to_origin", "item"), List.of("amount"));
        upsertMeta = dialect.upsert("meta", List.of("name"), List.of("value"));

        val config = new HikariConfig();
        config.setPoolName("provenance");
        config.setDriverClassName(dialect.driver());
        config.setJdbcUrl(dialect.url(database, dataDirectory));
        config.setUsername(database.user().isEmpty() ? null : database.user());
        config.setPassword(database.password().isEmpty() ? null : database.password());
        config.setConnectionInitSql(dialect.initSql());
        config.setMaximumPoolSize(dialect == Dialect.SQLITE ? 1 : REMOTE_POOL_SIZE);
        config.setConnectionTimeout(CONNECT_TIMEOUT_MILLIS);
        // Start without connecting: an unreachable database then surfaces below as an SQLException, not a Hikari one.
        config.setInitializationFailTimeout(-1);
        source = new HikariDataSource(config);

        try (val connection = source.getConnection();
                val statement = connection.createStatement()) {
            if (schemaVersion(statement) != SCHEMA_VERSION) {
                createSchema(statement);
            }
        } catch (SQLException | RuntimeException e) {
            source.close();
            throw e;
        }
    }

    private long schemaVersion(Statement statement) throws SQLException {
        statement.execute("CREATE TABLE IF NOT EXISTS schema_version (version " + dialect.bigint() + " NOT NULL)");
        try (val rows = statement.executeQuery("SELECT version FROM schema_version")) {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }

    private void createSchema(Statement statement) throws SQLException {
        val bigint = dialect.bigint();
        val key = dialect.key();
        val text = dialect.longText();

        for (val table : List.of("pool", "flow", "event", "meta", "holder_info")) {
            statement.execute("DROP TABLE IF EXISTS " + table);
        }
        statement.execute("DELETE FROM schema_version");
        statement.execute("CREATE TABLE pool (holder_kind " + key + " NOT NULL, holder_id " + key + " NOT NULL, item "
                + key + " NOT NULL, count " + bigint + " NOT NULL, origin " + text + " NOT NULL, custody " + text
                + " NOT NULL, PRIMARY KEY (holder_kind, holder_id, item))" + dialect.tableOptions(true));
        statement.execute("CREATE TABLE flow (from_origin " + key + " NOT NULL, to_origin " + key + " NOT NULL, item "
                + key + " NOT NULL, amount DOUBLE PRECISION NOT NULL, PRIMARY KEY (from_origin, to_origin, item))"
                + dialect.tableOptions(true));
        statement.execute("CREATE TABLE meta (name " + key + " PRIMARY KEY, value " + bigint + " NOT NULL)"
                + dialect.tableOptions(true));
        statement.execute("CREATE TABLE event (seq " + bigint + " PRIMARY KEY, time " + bigint + " NOT NULL,"
                + " cause " + key + " NOT NULL, type " + key + " NOT NULL, holder " + key + " NOT NULL, other " + key
                + ", item " + key + ", amount " + bigint + " NOT NULL, origin " + text + ", custody " + text
                + ", detail " + text + ", durable " + bigint + " NOT NULL, holder_type " + key + ", holder_label "
                + text + ", other_type " + key + ", other_label " + text + ", until " + bigint + " NOT NULL, chunk "
                + key + ")" + dialect.tableOptions(false));
        statement.execute("CREATE INDEX event_holder ON event (holder, seq)");
        statement.execute("CREATE INDEX event_other ON event (other, seq)");
        statement.execute("INSERT INTO schema_version VALUES (" + SCHEMA_VERSION + ")");
    }

    /**
     * Fills an empty ledger with the snapshot, then replays journal events newer than it. Replayed holders are dirty,
     * so the next flush folds the tail into the snapshot; the journal itself needs no rewrite.
     */
    public void load(Ledger ledger) throws SQLException {
        try (val connection = source.getConnection();
                val statement = connection.createStatement()) {
            try (val rows =
                    statement.executeQuery("SELECT holder_kind, holder_id, item, count, origin, custody FROM pool")) {
                while (rows.next()) {
                    val holder = new Holder(Kind.valueOf(rows.getString(1)), rows.getString(2));
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

            val lastSeq = meta(connection, LAST_SEQ);
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
        try (val connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try (val delete = connection.prepareStatement("DELETE FROM pool WHERE holder_kind = ? AND holder_id = ?");
                    val insert = connection.prepareStatement("INSERT INTO pool VALUES (?, ?, ?, ?, ?, ?)");
                    val flow = connection.prepareStatement(upsertFlow);
                    val event = connection.prepareStatement(insertEvent);
                    val meta = connection.prepareStatement(upsertMeta)) {

                for (val entry : changes.holders().entrySet()) {
                    val holder = entry.getKey();
                    delete.setString(1, holder.id().kind().name());
                    delete.setString(2, holder.id().id());
                    delete.addBatch();

                    for (val row : entry.getValue()) {
                        insert.setString(1, holder.id().kind().name());
                        insert.setString(2, holder.id().id());
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
                    bind(event, EventRow.of(envelope));
                    event.addBatch();
                }
                meta.setString(1, LAST_SEQ);
                meta.setLong(2, changes.lastSeq());

                delete.executeBatch();
                insert.executeBatch();
                flow.executeBatch();
                event.executeBatch();
                meta.executeUpdate();
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private static void bind(PreparedStatement event, EventRow row) throws SQLException {
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
        event.setString(13, row.holderInfo() == null ? null : row.holderInfo().type());
        event.setString(14, row.holderInfo() == null ? null : row.holderInfo().label());
        event.setString(15, row.otherInfo() == null ? null : row.otherInfo().type());
        event.setString(16, row.otherInfo() == null ? null : row.otherInfo().label());
        event.setLong(17, row.until());
        event.setString(18, row.chunk());
    }

    @Override
    public List<Envelope> history(Holder holder, @Nullable ItemKey item, int limit) throws SQLException {
        val events = new ArrayList<Envelope>();
        try (val connection = source.getConnection()) {
            for (val row : touching(connection, EventRow.encode(holder), item, Long.MIN_VALUE, limit)) {
                events.add(row.toEnvelope());
            }
        }
        return events;
    }

    @Override
    public Trace trace(Holder root, @Nullable ItemKey item, long since, int depth, int limit) throws SQLException {
        val found = new TreeMap<Long, EventRow>();
        val visited = new HashSet<String>();
        var frontier = List.of(EventRow.encode(root));
        visited.add(frontier.get(0));

        try (val connection = source.getConnection()) {
            for (var hop = 0; hop < depth && !frontier.isEmpty() && found.size() <= limit; hop++) {
                val next = new ArrayList<String>();
                // A dropped item is only a stop between two holders: it is walked in the same hop, not counted as one.
                val queue = new ArrayDeque<>(frontier);
                while (!queue.isEmpty()) {
                    for (val row : touching(connection, queue.poll(), item, since, limit + 1)) {
                        found.put(row.seq(), row);
                        visit(row.holder(), row.holderInfo(), visited, queue, next);
                        visit(row.other(), row.otherInfo(), visited, queue, next);
                    }
                }
                frontier = next;
            }
        }

        val truncated = found.size() > limit;
        while (found.size() > limit) {
            found.pollFirstEntry();
        }
        val events = new ArrayList<Envelope>(found.size());
        for (val row : found.values()) {
            events.add(row.toEnvelope());
        }
        return new Trace(events, truncated);
    }

    private static void visit(
            @Nullable String holder,
            @Nullable HolderMeta info,
            Set<String> visited,
            Collection<String> sameHop,
            Collection<String> nextHop) {
        if (holder != null && visited.add(holder)) {
            (info != null && ITEM_ENTITY.equals(info.type()) ? sameHop : nextHop).add(holder);
        }
    }

    /**
     * Newest-first rows where {@code holder} is either side, from {@code since} on, optionally touching {@code item}.
     */
    private List<EventRow> touching(Connection connection, String holder, @Nullable ItemKey item, long since, int limit)
            throws SQLException {
        val sql = "SELECT " + EVENT_COLUMNS + " FROM event WHERE (holder = ? OR other = ?) AND time >= ?"
                + (item == null ? "" : " AND (item = ? OR " + dialect.contains("detail") + ")")
                + " ORDER BY seq DESC LIMIT ?";
        val rows = new ArrayList<EventRow>();

        try (val query = connection.prepareStatement(sql)) {
            var index = 1;
            query.setString(index++, holder);
            query.setString(index++, holder);
            query.setLong(index++, since);
            if (item != null) {
                query.setString(index++, item.key());
                query.setString(index++, "|" + item.key() + "=");
            }
            query.setInt(index, limit);
            try (val result = query.executeQuery()) {
                while (result.next()) {
                    rows.add(read(result));
                }
            }
        }
        return rows;
    }

    @Override
    public void prune(long now) throws SQLException {
        try (val connection = source.getConnection();
                val delete = connection.prepareStatement(
                        "DELETE FROM event WHERE time < CASE durable WHEN 1 THEN ? ELSE ? END")) {
            delete.setLong(1, now - retention.durable().toMillis());
            delete.setLong(2, now - retention.automation().toMillis());
            delete.executeUpdate();
        }
    }

    /**
     * Adds up the similar events of every bucket that has ended (see {@link Bucketing}). Only events the snapshot
     * already contains are touched, so replay is unaffected; each bucket is rewritten in one transaction.
     */
    @Override
    public void compact(long now) throws SQLException {
        val settled = (now - SETTLE_MILLIS) / bucketMillis * bucketMillis;
        try (val connection = source.getConnection()) {
            val done = meta(connection, COMPACTED);
            val starts = new ArrayList<Long>();
            try (val query = connection.prepareStatement("SELECT DISTINCT time - time % " + bucketMillis
                    + " FROM event WHERE time >= ? AND time < ? ORDER BY 1")) {
                query.setLong(1, done);
                query.setLong(2, settled);
                try (val result = query.executeQuery()) {
                    while (result.next()) {
                        starts.add(result.getLong(1));
                    }
                }
            }
            for (val start : starts) {
                compactBucket(connection, start, start + bucketMillis);
            }
            if (settled > done) {
                try (val statement = connection.prepareStatement(upsertMeta)) {
                    statement.setString(1, COMPACTED);
                    statement.setLong(2, settled);
                    statement.executeUpdate();
                }
            }
        }
    }

    private void compactBucket(Connection connection, long from, long to) throws SQLException {
        val lastSeq = meta(connection, LAST_SEQ);
        val rows = new ArrayList<EventRow>();
        try (val query = connection.prepareStatement(
                "SELECT " + EVENT_COLUMNS + " FROM event WHERE time >= ? AND time < ? AND seq <= ? ORDER BY seq")) {
            query.setLong(1, from);
            query.setLong(2, to);
            query.setLong(3, lastSeq);
            try (val result = query.executeQuery()) {
                while (result.next()) {
                    rows.add(read(result));
                }
            }
        }
        val compact = Bucketing.compact(rows);
        if (compact.equals(rows)) {
            return;
        }

        connection.setAutoCommit(false);
        try (val delete = connection.prepareStatement("DELETE FROM event WHERE time >= ? AND time < ? AND seq <= ?");
                val insert = connection.prepareStatement(insertEvent)) {
            delete.setLong(1, from);
            delete.setLong(2, to);
            delete.setLong(3, lastSeq);
            delete.executeUpdate();
            for (val row : compact) {
                bind(insert, row);
                insert.addBatch();
            }
            insert.executeBatch();
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static long meta(Connection connection, String name) throws SQLException {
        try (val query = connection.prepareStatement("SELECT value FROM meta WHERE name = ?")) {
            query.setString(1, name);
            try (val result = query.executeQuery()) {
                return result.next() ? result.getLong(1) : 0;
            }
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
                rows.getInt(12) == 1,
                info(rows.getString(13), rows.getString(14)),
                info(rows.getString(15), rows.getString(16)),
                rows.getLong(17),
                rows.getString(18));
    }

    private static @Nullable HolderMeta info(@Nullable String type, @Nullable String label) {
        return type == null ? null : new HolderMeta(type, label, null);
    }

    @Override
    public void close() {
        source.close();
    }
}

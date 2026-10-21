package dev.keelfy.provenance.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.core.LedgerChanges;
import dev.keelfy.provenance.core.event.Envelope;
import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.HolderId;
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
import java.util.stream.Stream;
import lombok.val;
import org.jspecify.annotations.Nullable;

/**
 * SQL persistence (SQLite, MySQL/MariaDB or PostgreSQL, see {@link Dialect}) for a {@link Ledger}: one row per pool and
 * per flow edge (the snapshot) plus the event journal. A batch writes snapshot rows, events and {@code last_seq} in one
 * transaction, so on load the journal tail after {@code last_seq} is exactly what the snapshot lacks. Connections come
 * from a small HikariCP pool, so a connection the server dropped is replaced instead of failing every later write.
 * Callers still do not overlap: {@link WriteBehind} serializes access after {@link #load}.
 *
 * <p>Several servers may share one database: every row carries the {@code server} that wrote it, and a store loads,
 * compacts and prunes only its own rows. A player's history is the exception, it reads across servers: a player moves
 * between them, a block does not.
 *
 * @author keelfy
 */
public final class SqlStore implements ChangeSink {

    /** No migration: a database of another version is dropped (pre-release). */
    private static final int SCHEMA_VERSION = 7;

    private static final String LAST_SEQ = "last_seq";
    private static final String COMPACTED = "compacted";
    private static final String SEEN_SEEDED = "seen_seeded";
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
    /** The server comes last, so it does not shift the columns {@link EventRow} reads. */
    private static final int SERVER_COLUMN = EVENT_COLUMN_LIST.size() + 1;

    private final Dialect dialect;
    private final String server;
    private final Retention retention;
    private final long bucketMillis;
    private final HikariDataSource source;
    private final String insertEvent;
    private final String upsertFlow;
    private final String upsertMeta;
    private final String insertSeen;

    /**
     * @param server tells this server's rows from those of other servers in the same database
     * @param dataDirectory where a SQLite file lives
     * @param bucket the span of time whose similar events {@link #compact} adds up
     */
    public SqlStore(String server, Database database, Path dataDirectory, Retention retention, Duration bucket)
            throws SQLException {
        this.server = server;
        dialect = database.dialect();
        this.retention = retention;
        this.bucketMillis = bucket.toMillis();
        insertEvent = dialect.insertIgnore("event", withServer(EVENT_COLUMN_LIST), "seq");
        upsertFlow = dialect.upsert("flow", List.of("server", "from_origin", "to_origin", "item"), List.of("amount"));
        upsertMeta = dialect.upsert("meta", List.of("server", "name"), List.of("value"));
        insertSeen = dialect.insertIgnore("seen", List.of("server", "holder_kind", "holder_id"), "holder_id");

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
            createSeen(statement);
            seedSeen(connection);
        } catch (SQLException | RuntimeException e) {
            source.close();
            throw e;
        }
    }

    private static List<String> withServer(List<String> columns) {
        return Stream.concat(columns.stream(), Stream.of("server")).toList();
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

        for (val table : List.of("pool", "flow", "event", "meta", "holder_info", "seen")) {
            statement.execute("DROP TABLE IF EXISTS " + table);
        }
        statement.execute("DELETE FROM schema_version");
        statement.execute("CREATE TABLE pool (holder_kind " + key + " NOT NULL, holder_id " + key + " NOT NULL, item "
                + key + " NOT NULL, count " + bigint + " NOT NULL, origin " + text + " NOT NULL, custody " + text
                + " NOT NULL, server " + key + " NOT NULL, PRIMARY KEY (server, holder_kind, holder_id, item))"
                + dialect.tableOptions(true));
        statement.execute("CREATE TABLE flow (from_origin " + key + " NOT NULL, to_origin " + key + " NOT NULL, item "
                + key + " NOT NULL, amount DOUBLE PRECISION NOT NULL, server " + key
                + " NOT NULL, PRIMARY KEY (server, from_origin, to_origin, item))" + dialect.tableOptions(true));
        statement.execute("CREATE TABLE meta (name " + key + " NOT NULL, value " + bigint + " NOT NULL, server " + key
                + " NOT NULL, PRIMARY KEY (server, name))" + dialect.tableOptions(true));
        statement.execute("CREATE TABLE event (seq " + bigint + " NOT NULL, time " + bigint + " NOT NULL,"
                + " cause " + key + " NOT NULL, type " + key + " NOT NULL, holder " + key + " NOT NULL, other " + key
                + ", item " + key + ", amount " + bigint + " NOT NULL, origin " + text + ", custody " + text
                + ", detail " + text + ", durable " + bigint + " NOT NULL, holder_type " + key + ", holder_label "
                + text + ", other_type " + key + ", other_label " + text + ", until " + bigint + " NOT NULL, chunk "
                + key + ", server " + key + " NOT NULL, PRIMARY KEY (server, seq))" + dialect.tableOptions(false));
        statement.execute("CREATE INDEX event_holder ON event (holder, time)");
        statement.execute("CREATE INDEX event_other ON event (other, time)");
        statement.execute("INSERT INTO schema_version VALUES (" + SCHEMA_VERSION + ")");
    }

    /** Came after schema 7 and only adds rows, so it needs no reset of what is stored. */
    private void createSeen(Statement statement) throws SQLException {
        val key = dialect.key();
        statement.execute("CREATE TABLE IF NOT EXISTS seen (holder_kind " + key + " NOT NULL, holder_id " + key
                + " NOT NULL, server " + key + " NOT NULL, PRIMARY KEY (server, holder_kind, holder_id))"
                + dialect.tableOptions(true));
    }

    /**
     * A store written before {@code seen} existed already tracks its holders: they count as looked into, or every
     * unexplained item in them would pass for one from before tracking.
     */
    private void seedSeen(Connection connection) throws SQLException {
        if (meta(connection, SEEN_SEEDED) != 0) {
            return;
        }
        connection.setAutoCommit(false);
        try (val seed = connection.prepareStatement("INSERT INTO seen (server, holder_kind, holder_id)"
                        + " SELECT DISTINCT server, holder_kind, holder_id FROM pool WHERE server = ?");
                val meta = connection.prepareStatement(upsertMeta)) {
            seed.setString(1, server);
            seed.executeUpdate();
            bindMeta(meta, SEEN_SEEDED, 1);
            meta.executeUpdate();
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /**
     * Fills an empty ledger with the snapshot, then replays journal events newer than it. Replayed holders are dirty,
     * so the next flush folds the tail into the snapshot; the journal itself needs no rewrite.
     */
    public void load(Ledger ledger) throws SQLException {
        try (val connection = source.getConnection()) {
            try (val rows = query(
                    connection,
                    "SELECT holder_kind, holder_id, item, count, origin, custody FROM pool WHERE server = ?")) {
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
            try (val rows =
                    query(connection, "SELECT from_origin, to_origin, item, amount FROM flow WHERE server = ?")) {
                while (rows.next()) {
                    ledger.restore(new LedgerChanges.FlowRow(
                            new OriginId(rows.getString(1)),
                            new OriginId(rows.getString(2)),
                            new ItemKey(rows.getString(3)),
                            rows.getDouble(4)));
                }
            }

            try (val rows = query(connection, "SELECT holder_kind, holder_id FROM seen WHERE server = ?")) {
                while (rows.next()) {
                    ledger.restoreSeen(new HolderId(Kind.valueOf(rows.getString(1)), rows.getString(2)));
                }
            }

            val lastSeq = meta(connection, LAST_SEQ);
            ledger.restoreSeq(lastSeq);

            try (val rows = query(
                    connection,
                    "SELECT " + EVENT_COLUMNS + " FROM event WHERE server = ? AND seq > " + lastSeq
                            + " ORDER BY seq")) {
                while (rows.next()) {
                    ledger.replay(read(rows).toEnvelope());
                }
            }
        }
    }

    /** Runs {@code sql}, whose only {@code ?} is this server's key. */
    private ResultSet query(Connection connection, String sql) throws SQLException {
        val statement = connection.prepareStatement(sql);
        statement.closeOnCompletion();
        statement.setString(1, server);
        return statement.executeQuery();
    }

    /** Applies one batch atomically: changed holders are replaced wholesale, flow totals overwritten. */
    @Override
    public void write(LedgerChanges changes) throws SQLException {
        try (val connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try (val delete = connection.prepareStatement(
                            "DELETE FROM pool WHERE server = ? AND holder_kind = ? AND holder_id = ?");
                    val insert = connection.prepareStatement(
                            "INSERT INTO pool (server, holder_kind, holder_id, item, count, origin, custody) VALUES (?, ?, ?, ?, ?, ?, ?)");
                    val flow = connection.prepareStatement(upsertFlow);
                    val event = connection.prepareStatement(insertEvent);
                    val meta = connection.prepareStatement(upsertMeta);
                    val seen = connection.prepareStatement(insertSeen)) {

                for (val entry : changes.holders().entrySet()) {
                    val holder = entry.getKey();
                    delete.setString(1, server);
                    delete.setString(2, holder.id().kind().name());
                    delete.setString(3, holder.id().id());
                    delete.addBatch();

                    for (val row : entry.getValue()) {
                        insert.setString(1, server);
                        insert.setString(2, holder.id().kind().name());
                        insert.setString(3, holder.id().id());
                        insert.setString(4, row.item().key());
                        insert.setLong(5, row.count());
                        insert.setString(6, VectorCodec.encode(row.origin()));
                        insert.setString(7, VectorCodec.encode(row.custody()));
                        insert.addBatch();
                    }
                }
                for (val row : changes.flows()) {
                    flow.setString(1, server);
                    flow.setString(2, row.from().key());
                    flow.setString(3, row.to().key());
                    flow.setString(4, row.item().key());
                    flow.setDouble(5, row.amount());
                    flow.addBatch();
                }

                for (val envelope : changes.events()) {
                    bind(event, EventRow.of(envelope), server);
                    event.addBatch();
                }
                for (val holder : changes.seen()) {
                    seen.setString(1, server);
                    seen.setString(2, holder.kind().name());
                    seen.setString(3, holder.id());
                    seen.addBatch();
                }
                bindMeta(meta, LAST_SEQ, changes.lastSeq());

                delete.executeBatch();
                insert.executeBatch();
                flow.executeBatch();
                event.executeBatch();
                seen.executeBatch();
                meta.executeUpdate();
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private void bindMeta(PreparedStatement meta, String name, long value) throws SQLException {
        meta.setString(1, server);
        meta.setString(2, name);
        meta.setLong(3, value);
    }

    private static void bind(PreparedStatement event, EventRow row, String server) throws SQLException {
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
        event.setString(SERVER_COLUMN, server);
    }

    @Override
    public List<Envelope> history(Holder holder, @Nullable ItemKey item, int limit) throws SQLException {
        val events = new ArrayList<Envelope>();
        try (val connection = source.getConnection()) {
            // A player moves between servers and takes their history along; a block stands where it stands.
            for (val stored :
                    touching(connection, EventRow.encode(holder), item, Long.MIN_VALUE, limit, holder.isPlayer())) {
                events.add(stored.toEnvelope(server));
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
                    for (val stored : touching(connection, queue.poll(), item, since, limit + 1, false)) {
                        val row = stored.row();
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

    /** An event row and the server that journaled it. */
    private record Stored(EventRow row, String owner) {

        Envelope toEnvelope(String self) {
            return row.toEnvelope(owner.equals(self) ? null : owner);
        }
    }

    /**
     * Newest-first rows where {@code holder} is either side, from {@code since} on, optionally touching {@code item};
     * of this server only unless {@code everywhere}.
     */
    private List<Stored> touching(
            Connection connection, String holder, @Nullable ItemKey item, long since, int limit, boolean everywhere)
            throws SQLException {
        val sql = "SELECT " + EVENT_COLUMNS + ", server FROM event WHERE (holder = ? OR other = ?) AND time >= ?"
                + (everywhere ? "" : " AND server = ?")
                + (item == null ? "" : " AND (item = ? OR " + dialect.contains("detail") + ")")
                + " ORDER BY time DESC, seq DESC LIMIT ?";
        val rows = new ArrayList<Stored>();

        try (val query = connection.prepareStatement(sql)) {
            var index = 1;
            query.setString(index++, holder);
            query.setString(index++, holder);
            query.setLong(index++, since);
            if (!everywhere) {
                query.setString(index++, server);
            }
            if (item != null) {
                query.setString(index++, item.key());
                query.setString(index++, "|" + item.key() + "=");
            }
            query.setInt(index, limit);
            try (val result = query.executeQuery()) {
                while (result.next()) {
                    rows.add(new Stored(read(result), result.getString(SERVER_COLUMN)));
                }
            }
        }
        return rows;
    }

    @Override
    public void prune(long now) throws SQLException {
        try (val connection = source.getConnection();
                val delete = connection.prepareStatement(
                        "DELETE FROM event WHERE server = ? AND time < CASE durable WHEN 1 THEN ? ELSE ? END")) {
            delete.setString(1, server);
            delete.setLong(2, now - retention.durable().toMillis());
            delete.setLong(3, now - retention.automation().toMillis());
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
                    + " FROM event WHERE server = ? AND time >= ? AND time < ? ORDER BY 1")) {
                query.setString(1, server);
                query.setLong(2, done);
                query.setLong(3, settled);
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
                    bindMeta(statement, COMPACTED, settled);
                    statement.executeUpdate();
                }
            }
        }
    }

    private void compactBucket(Connection connection, long from, long to) throws SQLException {
        val lastSeq = meta(connection, LAST_SEQ);
        val rows = new ArrayList<EventRow>();
        try (val query = connection.prepareStatement("SELECT " + EVENT_COLUMNS
                + " FROM event WHERE server = ? AND time >= ? AND time < ? AND seq <= ? ORDER BY seq")) {
            query.setString(1, server);
            query.setLong(2, from);
            query.setLong(3, to);
            query.setLong(4, lastSeq);
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
        try (val delete = connection.prepareStatement(
                        "DELETE FROM event WHERE server = ? AND time >= ? AND time < ? AND seq <= ?");
                val insert = connection.prepareStatement(insertEvent)) {
            delete.setString(1, server);
            delete.setLong(2, from);
            delete.setLong(3, to);
            delete.setLong(4, lastSeq);
            delete.executeUpdate();
            for (val row : compact) {
                bind(insert, row, server);
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

    private long meta(Connection connection, String name) throws SQLException {
        try (val query = connection.prepareStatement("SELECT value FROM meta WHERE server = ? AND name = ?")) {
            query.setString(1, server);
            query.setString(2, name);
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

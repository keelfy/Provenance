package dev.keelfy.provenance.storage;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import lombok.val;
import org.jspecify.annotations.Nullable;

/**
 * Where {@link SqlStore}'s SQL differs between databases: the driver, the URL, column types and upsert syntax.
 *
 * @author keelfy
 */
public enum Dialect {
    SQLITE("org.sqlite.JDBC", 0),
    /** MySQL and MariaDB, both through the MariaDB driver. */
    MYSQL("org.mariadb.jdbc.Driver", 3306),
    POSTGRESQL("org.postgresql.Driver", 5432);

    private final String driver;
    private final int defaultPort;

    Dialect(String driver, int defaultPort) {
        this.driver = driver;
        this.defaultPort = defaultPort;
    }

    /** @throws IllegalArgumentException if the name is not a supported database */
    public static Dialect parse(String name) {
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "sqlite" -> SQLITE;
            case "mysql", "mariadb" -> MYSQL;
            case "postgresql", "postgres" -> POSTGRESQL;
            default ->
                throw new IllegalArgumentException(
                        "config.yml: storage.type must be sqlite, mysql or postgresql, got " + name);
        };
    }

    public String driver() {
        return driver;
    }

    public int defaultPort() {
        return defaultPort;
    }

    String url(Database database, Path dataDirectory) {
        return switch (this) {
            case SQLITE ->
                "jdbc:sqlite:" + dataDirectory.resolve(database.name()).toAbsolutePath();
            case MYSQL -> "jdbc:mariadb://" + database.host() + ":" + database.port() + "/" + database.name();
            case POSTGRESQL -> "jdbc:postgresql://" + database.host() + ":" + database.port() + "/" + database.name();
        };
    }

    /** Run on every new connection. */
    @Nullable String initSql() {
        return this == SQLITE ? "PRAGMA journal_mode=WAL" : null;
    }

    /** 64-bit integer; in SQLite it must be spelled {@code INTEGER} for {@code PRIMARY KEY} to alias the rowid. */
    String bigint() {
        return this == SQLITE ? "INTEGER" : "BIGINT";
    }

    /** Short enough that a composite primary key stays under MySQL's index size limit. */
    String key() {
        return "VARCHAR(191)";
    }

    String longText() {
        return this == MYSQL ? "LONGTEXT" : "TEXT";
    }

    /** Case-sensitive utf8mb4 for MySQL (its default is neither); a rowid-less layout for SQLite key-only tables. */
    String tableOptions(boolean keyed) {
        return switch (this) {
            case SQLITE -> keyed ? " WITHOUT ROWID" : "";
            case MYSQL -> " DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin";
            case POSTGRESQL -> "";
        };
    }

    /** Insert, or overwrite the {@code values} of the row with the same {@code keys}. */
    String upsert(String table, List<String> keys, List<String> values) {
        val insert = insert(table, Stream.concat(keys.stream(), values.stream()).toList());
        if (this == MYSQL) {
            return insert + " ON DUPLICATE KEY UPDATE "
                    + String.join(
                            ", ",
                            values.stream().map(v -> v + " = VALUES(" + v + ")").toList());
        }
        return insert + " ON CONFLICT (" + String.join(", ", keys) + ") DO UPDATE SET "
                + String.join(
                        ", ", values.stream().map(v -> v + " = excluded." + v).toList());
    }

    /** Insert unless a row with the same {@code key} exists. */
    String insertIgnore(String table, List<String> columns, String key) {
        val insert = insert(table, columns);
        return this == MYSQL
                ? insert + " ON DUPLICATE KEY UPDATE " + key + " = " + key
                : insert + " ON CONFLICT DO NOTHING";
    }

    /** One {@code ?}: the text that must be found in {@code column}. */
    String contains(String column) {
        return (this == POSTGRESQL ? "strpos(" : "instr(") + column + ", ?) > 0";
    }

    private static String insert(String table, List<String> columns) {
        return "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES ("
                + String.join(", ", Collections.nCopies(columns.size(), "?")) + ")";
    }
}

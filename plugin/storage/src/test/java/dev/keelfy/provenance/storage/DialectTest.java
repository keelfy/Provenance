package dev.keelfy.provenance.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class DialectTest {

    @Test
    void parsesNamesAndAliases() {
        assertThat(Dialect.parse("SQLite")).isEqualTo(Dialect.SQLITE);
        assertThat(Dialect.parse("mariadb")).isEqualTo(Dialect.MYSQL);
        assertThat(Dialect.parse(" postgres ")).isEqualTo(Dialect.POSTGRESQL);
        assertThatThrownBy(() -> Dialect.parse("oracle")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void upsertUsesEachDatabasesSyntax() {
        var keys = List.of("a", "b");
        var values = List.of("v");

        assertThat(Dialect.POSTGRESQL.upsert("t", keys, values))
                .isEqualTo("INSERT INTO t (a, b, v) VALUES (?, ?, ?) ON CONFLICT (a, b) DO UPDATE SET v = excluded.v");
        assertThat(Dialect.MYSQL.upsert("t", keys, values))
                .isEqualTo("INSERT INTO t (a, b, v) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE v = VALUES(v)");
        assertThat(Dialect.MYSQL.insertIgnore("t", List.of("a", "b"), "a")).endsWith("ON DUPLICATE KEY UPDATE a = a");
        assertThat(Dialect.SQLITE.insertIgnore("t", List.of("a", "b"), "a")).endsWith("ON CONFLICT DO NOTHING");
    }
}

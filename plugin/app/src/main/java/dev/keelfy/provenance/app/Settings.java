package dev.keelfy.provenance.app;

import dev.keelfy.provenance.app.port.ConfigSource;
import dev.keelfy.provenance.storage.Database;
import dev.keelfy.provenance.storage.Dialect;
import dev.keelfy.provenance.storage.Retention;
import java.time.Duration;
import lombok.val;

/**
 * Everything tunable in {@code config.yml}. Keys missing from the server's file fall back to the bundled one.
 *
 * @author keelfy
 */
public record Settings(
        boolean mergeAutomation,
        Database database,
        Retention retention,
        Duration bucket,
        long flushTicks,
        long pruneTicks,
        Duration shutdownTimeout,
        long reconcileTicks,
        double blockRadius,
        double dispenseRadius,
        double ejectRadius,
        double deathRadius,
        double spillRadius,
        int blockReach,
        int historyRows,
        int blockRows,
        int playerRows,
        int linkRows,
        int topOrigins,
        String webEndpoint,
        String webSecret,
        int webTtlHours,
        int webDepth,
        int webMaxEvents) {

    private static final int TICKS_PER_SECOND = 20;
    private static final String DATABASE_FILE = "provenance.db";

    /** @throws IllegalArgumentException if a value is not positive */
    public static Settings from(ConfigSource config) {
        val seconds = TICKS_PER_SECOND;
        val hours = 3600L * TICKS_PER_SECOND;
        return new Settings(
                config.getBoolean("journal.merge-automation"),
                database(config),
                new Retention(
                        Duration.ofDays(whole(config, "journal.retention-days")),
                        Duration.ofDays(whole(config, "journal.automation-retention-days"))),
                Duration.ofMinutes(whole(config, "journal.bucket-minutes")),
                whole(config, "storage.flush-interval-seconds") * seconds,
                whole(config, "storage.prune-interval-hours") * hours,
                Duration.ofSeconds(whole(config, "storage.shutdown-timeout-seconds")),
                whole(config, "reconcile-interval-seconds") * seconds,
                radius(config, "attribution.block-radius"),
                radius(config, "attribution.dispense-radius"),
                radius(config, "attribution.eject-radius"),
                radius(config, "attribution.death-radius"),
                radius(config, "attribution.spill-radius"),
                (int) whole(config, "commands.block-reach"),
                (int) whole(config, "commands.history-rows"),
                (int) whole(config, "commands.block-rows"),
                (int) whole(config, "commands.player-rows"),
                (int) whole(config, "commands.link-rows"),
                (int) whole(config, "commands.top-origins"),
                trimmed(config, "web.endpoint"),
                trimmed(config, "web.secret"),
                (int) whole(config, "web.ttl-hours"),
                (int) whole(config, "web.depth"),
                (int) whole(config, "web.max-events"));
    }

    private static Database database(ConfigSource config) {
        val dialect = Dialect.parse(config.getString("storage.type", "sqlite"));
        if (dialect == Dialect.SQLITE) {
            return Database.sqlite(DATABASE_FILE);
        }

        val port = config.getLong("storage.port");
        return new Database(
                dialect,
                config.getString("storage.host", "localhost").trim(),
                (int) (port > 0 ? port : dialect.defaultPort()),
                config.getString("storage.database", "provenance").trim(),
                config.getString("storage.username", "").trim(),
                config.getString("storage.password", ""));
    }

    /** An empty endpoint or secret switches the web viewer off. */
    public boolean webEnabled() {
        return !webEndpoint.isEmpty() && !webSecret.isEmpty();
    }

    /** Drops a trailing slash so paths can be appended. */
    private static String trimmed(ConfigSource config, String key) {
        val value = config.getString(key, "").trim();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static long whole(ConfigSource config, String key) {
        val value = config.getLong(key);
        if (value < 1) {
            throw new IllegalArgumentException("config.yml: " + key + " must be at least 1, got " + value);
        }
        return value;
    }

    private static double radius(ConfigSource config, String key) {
        val value = config.getDouble(key);
        if (!(value > 0)) {
            throw new IllegalArgumentException("config.yml: " + key + " must be positive, got " + value);
        }
        return value;
    }
}

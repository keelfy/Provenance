package dev.keelfy.provenance;

import dev.keelfy.provenance.storage.Retention;
import java.time.Duration;
import lombok.val;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Everything tunable in {@code config.yml}. Keys missing from the server's file fall back to the bundled one.
 *
 * @author keelfy
 */
public record Settings(
        boolean mergeAutomation,
        Retention retention,
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
        int topOrigins) {

    private static final int TICKS_PER_SECOND = 20;

    /** @throws IllegalArgumentException if a value is not positive */
    public static Settings from(ConfigurationSection config) {
        val seconds = TICKS_PER_SECOND;
        val hours = 3600L * TICKS_PER_SECOND;
        return new Settings(
                config.getBoolean("journal.merge-automation"),
                new Retention(
                        Duration.ofDays(whole(config, "journal.retention-days")),
                        Duration.ofDays(whole(config, "journal.automation-retention-days"))),
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
                (int) whole(config, "commands.top-origins"));
    }

    private static long whole(ConfigurationSection config, String key) {
        val value = config.getLong(key);
        if (value < 1) {
            throw new IllegalArgumentException("config.yml: " + key + " must be at least 1, got " + value);
        }
        return value;
    }

    private static double radius(ConfigurationSection config, String key) {
        val value = config.getDouble(key);
        if (!(value > 0)) {
            throw new IllegalArgumentException("config.yml: " + key + " must be positive, got " + value);
        }
        return value;
    }
}

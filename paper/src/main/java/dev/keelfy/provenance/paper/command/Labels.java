package dev.keelfy.provenance.paper.command;

import java.util.Map;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import dev.keelfy.provenance.core.OriginId;
import dev.keelfy.provenance.core.TaintVector;
import lombok.experimental.UtilityClass;
import lombok.val;

/**
 * Human-readable names for origins and vectors in command output.
 *
 * @author keelfy
 */
@UtilityClass
public class Labels {

    private static final String ENV_PREFIX = "env:";
    private static final int TOP = 6;

    public @NotNull String of(@NotNull OriginId origin) {
        if (origin.isPlayer()) {
            val uuid = origin.playerId();
            val name = Bukkit.getOfflinePlayer(uuid).getName();
            return name != null ? name : uuid.toString().substring(0, 8);
        }
        val key = origin.key();
        return key.startsWith(ENV_PREFIX) ? key.substring(ENV_PREFIX.length()) : key;
    }

    /** {@code Alice 62%, unknown 30%, …} for the strongest shares. */
    public @NotNull String of(@NotNull TaintVector vector) {
        return top(vector.shares(), 1.0);
    }

    /** Same, for absolute amounts: shares are relative to the sum. */
    public @NotNull String ofAmounts(@NotNull Map<OriginId, Double> amounts) {
        val total = amounts.values().stream().mapToDouble(d -> d).sum();
        return total > 0 ? top(amounts, total) : "-";
    }

    private String top(Map<OriginId, Double> values, double total) {
        return values.entrySet().stream()
                .sorted(Map.Entry.<OriginId, Double>comparingByValue().reversed())
                .limit(TOP)
                .map(e -> of(e.getKey()) + " " + Math.round(e.getValue() / total * 100) + "%")
                .collect(Collectors.joining(", "));
    }
}

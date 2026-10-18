package dev.keelfy.provenance.paper.command;

import dev.keelfy.provenance.core.HolderId;
import dev.keelfy.provenance.core.HolderInfo;
import dev.keelfy.provenance.core.OriginId;
import dev.keelfy.provenance.core.TaintVector;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;
import lombok.val;
import org.bukkit.Bukkit;
import org.jspecify.annotations.Nullable;

/**
 * Human-readable names for origins and vectors in command output.
 *
 * @author keelfy
 */
@UtilityClass
public class Labels {

    private static final String ENV_PREFIX = "env:";

    public String of(OriginId origin) {
        if (origin.isPlayer()) {
            val uuid = origin.playerId();
            val name = Bukkit.getOfflinePlayer(uuid).getName();
            return name != null ? name : uuid.toString().substring(0, 8);
        }
        val key = origin.key();
        return key.startsWith(ENV_PREFIX) ? key.substring(ENV_PREFIX.length()) : key;
    }

    public String of(HolderId holder) {
        return switch (holder.kind()) {
            case PLAYER -> of(holder.asPlayerOrigin());
            case BLOCK -> holder.id().replace(':', ' ');
            case ENTITY -> "entity " + holder.id().substring(0, 8);
        };
    }

    /**
     * What a holder is, without its id: the player's name, or the custom name / type recorded with the event. The web
     * viewer shows the id next to it.
     */
    public String name(HolderId holder, @Nullable HolderInfo info) {
        return switch (holder.kind()) {
            case PLAYER -> of(holder.asPlayerOrigin());
            case BLOCK -> info != null ? info.displayName() : "Block";
            case ENTITY -> info != null ? info.displayName() : "Entity";
        };
    }

    /** {@code Alice 62%, unknown 30%, …} for the {@code top} strongest shares. */
    public String of(TaintVector vector, int top) {
        return top(vector.shares(), 1.0, top);
    }

    /** Same, for absolute amounts: shares are relative to the sum. */
    public String ofAmounts(Map<OriginId, Double> amounts, int top) {
        val total = amounts.values().stream().mapToDouble(d -> d).sum();
        return total > 0 ? top(amounts, total, top) : "-";
    }

    private String top(Map<OriginId, Double> values, double total, int limit) {
        return values.entrySet().stream()
                .sorted(Map.Entry.<OriginId, Double>comparingByValue().reversed())
                .limit(limit)
                .map(e -> of(e.getKey()) + " " + Math.round(e.getValue() / total * 100) + "%")
                .collect(Collectors.joining(", "));
    }
}

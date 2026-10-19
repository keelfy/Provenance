package dev.keelfy.provenance.app.command;

import dev.keelfy.provenance.app.port.PlayerDirectory;
import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.OriginId;
import dev.keelfy.provenance.core.taint.TaintVector;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.val;

/**
 * Human-readable names for origins and vectors in command output.
 *
 * @author keelfy
 */
@RequiredArgsConstructor
public final class Labels {

    private static final String ENV_PREFIX = "env:";

    private final PlayerDirectory players;

    public String of(OriginId origin) {
        if (origin.isPlayer()) {
            val uuid = origin.playerId();
            val name = players.nameOf(uuid);
            return name != null ? name : uuid.toString().substring(0, 8);
        }
        val key = origin.key();
        return key.startsWith(ENV_PREFIX) ? key.substring(ENV_PREFIX.length()) : key;
    }

    public String of(Holder holder) {
        return switch (holder.id().kind()) {
            case PLAYER -> of(holder.asPlayerOrigin());
            case BLOCK -> holder.id().id().replace(':', ' ');
            case ENTITY -> "entity " + holder.id().id().substring(0, 8);
        };
    }

    /**
     * What a holder is, without its id: the player's name, or the custom name / type recorded with the event. The web
     * viewer shows the id next to it.
     */
    public String name(Holder holder) {
        return switch (holder.id().kind()) {
            case PLAYER -> of(holder.asPlayerOrigin());
            case BLOCK, ENTITY -> holder.displayName();
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

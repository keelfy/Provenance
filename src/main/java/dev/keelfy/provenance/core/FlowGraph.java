package dev.keelfy.provenance.core;

import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import lombok.Getter;

/** 
 * Expected item counts moved from one player custodian to another: {@code F[from][to][item]}.
 * 
 * @author keelfy
 * */
@Getter 
public final class FlowGraph {

    private final @NotNull Map<OriginId, Map<OriginId, Map<ItemKey, Double>>> edges = new HashMap<>();

    void record(@NotNull OriginId from, @NotNull OriginId to, @NotNull ItemKey item, double amount) {
        if (amount <= 0 || from.equals(to)) {
            return;
        }
        edges.computeIfAbsent(from, k -> new HashMap<>())
                .computeIfAbsent(to, k -> new HashMap<>())
                .merge(item, amount, (a, b) -> a + b);
    }

    public double flow(@NotNull OriginId from, @NotNull OriginId to, @NotNull ItemKey item) {
        return edges.getOrDefault(from, Map.of())
                .getOrDefault(to, Map.of())
                .getOrDefault(item, 0.0);
    }

    /** Flow summed over all item types. */
    public double flow(@NotNull OriginId from, @NotNull OriginId to) {
        return edges.getOrDefault(from, Map.of())
                .getOrDefault(to, Map.of())
                .values()
                .stream()
                .mapToDouble(d -> d)
                .sum();
    }
}

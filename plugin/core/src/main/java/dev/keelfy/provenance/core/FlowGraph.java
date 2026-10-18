package dev.keelfy.provenance.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.val;

/**
 * Expected item counts moved from one player custodian to another: {@code F[from][to][item]}.
 *
 * @author keelfy
 */
@Getter
public final class FlowGraph {

    private final Map<OriginId, Map<OriginId, Map<ItemKey, Double>>> edges = new HashMap<>();

    @Getter(AccessLevel.NONE)
    private final Set<Edge> dirty = new HashSet<>();

    private record Edge(OriginId from, OriginId to, ItemKey item) {}

    void record(OriginId from, OriginId to, ItemKey item, double amount) {
        if (amount <= 0 || from.equals(to)) {
            return;
        }
        edges.computeIfAbsent(from, k -> new HashMap<>())
                .computeIfAbsent(to, k -> new HashMap<>())
                .merge(item, amount, (a, b) -> a + b);
        dirty.add(new Edge(from, to, item));
    }

    /** Loads a persisted total without marking it dirty. */
    void restore(OriginId from, OriginId to, ItemKey item, double amount) {
        edges.computeIfAbsent(from, k -> new HashMap<>())
                .computeIfAbsent(to, k -> new HashMap<>())
                .put(item, amount);
    }

    /** Current totals of edges changed since the last drain. */
    List<LedgerChanges.FlowRow> drainDirty() {
        val rows = new ArrayList<LedgerChanges.FlowRow>(dirty.size());
        for (val edge : dirty) {
            rows.add(new LedgerChanges.FlowRow(
                    edge.from(), edge.to(), edge.item(), flow(edge.from(), edge.to(), edge.item())));
        }
        dirty.clear();
        return rows;
    }

    public double flow(OriginId from, OriginId to, ItemKey item) {
        return edges.getOrDefault(from, Map.of()).getOrDefault(to, Map.of()).getOrDefault(item, 0.0);
    }

    /** Flow summed over all item types. */
    public double flow(OriginId from, OriginId to) {
        return edges.getOrDefault(from, Map.of()).getOrDefault(to, Map.of()).values().stream()
                .mapToDouble(d -> d)
                .sum();
    }
}

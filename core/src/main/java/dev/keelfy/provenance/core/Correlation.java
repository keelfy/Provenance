package dev.keelfy.provenance.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.val;

/**
 * Read-side queries over a {@link Ledger}: wealth attribution {@code W[P][Q]}, similarity and links.
 *
 * @author keelfy
 */
@RequiredArgsConstructor
public final class Correlation {

    private final Ledger ledger;

    /**
     * Expected item counts held by {@code player} (own inventory plus custody share of containers), by origin. Assumes
     * custody and origin are independent within a pool.
     */
    public Map<OriginId, Double> wealth(OriginId player) {
        val result = new HashMap<OriginId, Double>();
        for (val holder : ledger.getHolders()) {
            for (Pool pool : ledger.getPools(holder).values()) {
                double held = pool.getCount() * pool.getCustody().share(player);
                if (held <= 0) {
                    continue;
                }
                pool.getOrigin()
                        .shares()
                        .forEach((origin, share) -> result.merge(origin, held * share, (a, b) -> a + b));
            }
        }
        return result;
    }

    /** Cosine similarity of two players' wealth over player origins only (environment buckets are noise). */
    public double similarity(OriginId a, OriginId b) {
        val wa = playerOnly(wealth(a));
        val wb = playerOnly(wealth(b));
        double dot = 0;

        for (val entry : wa.entrySet()) {
            dot += entry.getValue() * wb.getOrDefault(entry.getKey(), 0.0);
        }

        double norm = norm(wa) * norm(wb);
        return norm == 0 ? 0 : dot / norm;
    }

    /** Players connected to {@code player} by flows or shared wealth, strongest flow first. */
    public List<Link> links(OriginId player) {
        val sourceEdges =
                ledger.getFlows().getEdges().getOrDefault(player, Map.of()).keySet();
        val candidates = new HashSet<OriginId>(sourceEdges);

        ledger.getFlows().getEdges().forEach((from, targets) -> {
            if (targets.containsKey(player)) {
                candidates.add(from);
            }
        });

        val playerWealth = playerOnly(wealth(player));
        candidates.addAll(playerWealth.keySet());
        candidates.remove(player);

        val links = new ArrayList<Link>();
        for (val other : candidates) {
            val playerToOther = ledger.getFlows().flow(player, other);
            val otherToPlayer = ledger.getFlows().flow(other, player);
            links.add(new Link(other, playerToOther, otherToPlayer, similarity(player, other)));
        }
        links.sort(Comparator.comparingDouble(Link::volume)
                .thenComparingDouble(Link::similarity)
                .reversed());
        return links;
    }

    public static Map<OriginId, Double> playerOnly(Map<OriginId, Double> wealth) {
        Map<OriginId, Double> result = new HashMap<>(wealth);
        result.keySet().removeIf(origin -> !origin.isPlayer());
        return result;
    }

    private static double norm(Map<OriginId, Double> vector) {
        return Math.sqrt(vector.values().stream().mapToDouble(v -> v * v).sum());
    }
}

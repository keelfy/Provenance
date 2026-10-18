package dev.keelfy.provenance.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.val;

/**
 * Immutable sparse distribution over origins; shares sum to 1 (or the vector is empty). Bounded to
 * {@link #MAX_ENTRIES}: the tail and shares below {@link #EPSILON} fold into {@link OriginId#OTHER}.
 *
 * @author keelfy
 */
@ToString
@EqualsAndHashCode
public final class TaintVector {

    public static final int MAX_ENTRIES = 16;
    public static final double EPSILON = 1e-4;
    public static final TaintVector EMPTY = new TaintVector(Map.of());

    @ToString.Include
    @EqualsAndHashCode.Include
    private final Map<OriginId, Double> shares;

    private TaintVector(Map<OriginId, Double> shares) {
        this.shares = shares;
    }

    public static TaintVector of(OriginId origin) {
        return new TaintVector(Map.of(origin, 1.0));
    }

    public static Builder builder() {
        return new Builder();
    }

    public double share(OriginId origin) {
        return shares.getOrDefault(origin, 0.0);
    }

    /** Entries ordered by descending share. */
    public Map<OriginId, Double> shares() {
        return shares;
    }

    public boolean isEmpty() {
        return shares.isEmpty();
    }

    /** Weighted mix of two vectors; weights are item counts. */
    public static TaintVector mix(TaintVector a, double weightA, TaintVector b, double weightB) {
        return builder().add(a, weightA).add(b, weightB).build();
    }

    /** Accumulates weighted vectors and normalizes on {@link #build()}. */
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class Builder {

        private final Map<OriginId, Double> mass = new HashMap<>();

        public Builder add(TaintVector vector, double weight) {
            if (weight <= 0) {
                return this;
            }
            vector.shares.forEach((origin, share) -> mass.merge(origin, share * weight, (d1, d2) -> d1 + d2));
            return this;
        }

        public Builder add(OriginId origin, double weight) {
            if (weight > 0) {
                mass.merge(origin, weight, (d1, d2) -> d1 + d2);
            }
            return this;
        }

        public TaintVector build() {
            val total = mass.values().stream().mapToDouble((d) -> d).sum();
            if (total <= 0) {
                return EMPTY;
            }

            val sorted = new ArrayList<Map.Entry<OriginId, Double>>(mass.entrySet());
            sorted.sort(Map.Entry.<OriginId, Double>comparingByValue().reversed());

            val kept = new LinkedHashMap<OriginId, Double>();
            double other = 0.0;

            for (val entry : sorted) {
                val share = entry.getValue() / total;
                val room = kept.size() < MAX_ENTRIES - 1;
                if (room && share >= EPSILON && !entry.getKey().equals(OriginId.OTHER)) {
                    kept.put(entry.getKey(), share);
                } else {
                    other += share;
                }
            }

            if (other > 0) {
                kept.put(OriginId.OTHER, other);
            }
            return new TaintVector(Collections.unmodifiableMap(kept));
        }
    }
}

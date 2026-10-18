package dev.keelfy.provenance.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaintVectorTest {

    private static final OriginId ALICE = OriginId.player(UUID.randomUUID());
    private static final OriginId BOB = OriginId.player(UUID.randomUUID());

    @Test
    void mixIsCountWeighted() {
        TaintVector mixed = TaintVector.mix(TaintVector.of(ALICE), 3, TaintVector.of(BOB), 1);

        assertThat(mixed.share(ALICE)).isCloseTo(0.75, within(1e-9));
        assertThat(mixed.share(BOB)).isCloseTo(0.25, within(1e-9));
    }

    @Test
    void mixWithEmptyIsIdentity() {
        assertThat(TaintVector.mix(TaintVector.EMPTY, 0, TaintVector.of(ALICE), 5))
                .isEqualTo(TaintVector.of(ALICE));
    }

    @Test
    void pruningKeepsBoundAndSum() {
        TaintVector.Builder builder = TaintVector.builder();
        for (int i = 0; i < 100; i++) {
            builder.add(OriginId.player(UUID.randomUUID()), 1);
        }
        TaintVector vector = builder.build();

        assertThat(vector.shares()).hasSizeLessThanOrEqualTo(TaintVector.MAX_ENTRIES);
        assertThat(vector.share(OriginId.OTHER)).isGreaterThan(0);
        assertThat(sum(vector)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void tinySharesFoldIntoOther() {
        TaintVector vector =
                TaintVector.builder().add(ALICE, 1_000_000).add(BOB, 1).build();

        assertThat(vector.share(BOB)).isZero();
        assertThat(vector.share(OriginId.OTHER)).isGreaterThan(0);
        assertThat(sum(vector)).isCloseTo(1.0, within(1e-9));
    }

    private static double sum(TaintVector vector) {
        return vector.shares().values().stream()
                .mapToDouble(Double::doubleValue)
                .sum();
    }
}

package dev.keelfy.provenance.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.keelfy.provenance.core.model.Cause;
import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.ItemKey;
import dev.keelfy.provenance.core.model.OriginId;
import dev.keelfy.provenance.core.taint.TaintVector;
import java.util.Map;
import java.util.UUID;
import lombok.val;
import org.junit.jupiter.api.Test;

class SettlementTest {

    private static final Cause CAUSE = Cause.CONTAINER;

    private static final ItemKey DIAMOND = new ItemKey("minecraft:diamond");
    private static final ItemKey STICK = new ItemKey("minecraft:stick");
    private static final ItemKey PICKAXE = new ItemKey("minecraft:diamond_pickaxe");

    private final UUID aliceId = UUID.randomUUID();
    private final Holder alice = Holder.player(aliceId);
    private final OriginId aliceOrigin = OriginId.player(aliceId);
    private final Holder chest = Holder.block("world", 1, 64, 1);
    private final Ledger ledger = new Ledger();

    @Test
    void oppositeChangesBecomeTransfers() {
        ledger.mint(CAUSE, alice, DIAMOND, 10, aliceOrigin);

        new Settlement(ledger, CAUSE, OriginId.UNKNOWN)
                .container(alice, Map.of(DIAMOND, 4L), chest, Map.of(DIAMOND, 6L));

        assertThat(ledger.getCount(alice, DIAMOND)).isEqualTo(4);
        assertThat(ledger.pool(chest, DIAMOND).getOrigin()).isEqualTo(TaintVector.of(aliceOrigin));
        assertThat(ledger.pool(chest, DIAMOND).getCustody()).isEqualTo(TaintVector.of(aliceOrigin));
    }

    @Test
    void unmatchedChangesAreReconciledWithGivenOrigin() {
        new Settlement(ledger, CAUSE, OriginId.CREATIVE).container(alice, Map.of(DIAMOND, 3L), chest, Map.of());

        assertThat(ledger.pool(alice, DIAMOND).getOrigin()).isEqualTo(TaintVector.of(OriginId.CREATIVE));
    }

    @Test
    void workstationOutputInheritsInputOrigin() {
        ledger.mint(CAUSE, alice, DIAMOND, 3, aliceOrigin);
        ledger.mint(CAUSE, alice, STICK, 2, OriginId.UNKNOWN);

        new Settlement(ledger, CAUSE, OriginId.UNKNOWN).workstation(alice, Map.of(PICKAXE, 1L));

        assertThat(ledger.getPools(alice)).containsOnlyKeys(PICKAXE);
        assertThat(ledger.pool(alice, PICKAXE).getOrigin().share(aliceOrigin)).isCloseTo(0.6, within(1e-9));
    }

    @Test
    void workstationOutputWithoutInputIsMintedNotCrafted() {
        new Settlement(ledger, CAUSE, OriginId.CREATIVE).workstation(alice, Map.of(DIAMOND, 3L));

        assertThat(ledger.pool(alice, DIAMOND).getOrigin()).isEqualTo(TaintVector.of(OriginId.CREATIVE));
    }

    @Test
    void workstationWithoutOutputBurns() {
        ledger.mint(CAUSE, alice, DIAMOND, 3, aliceOrigin);

        new Settlement(ledger, CAUSE, OriginId.UNKNOWN).workstation(alice, Map.of(DIAMOND, 1L));

        assertThat(ledger.getCount(alice, DIAMOND)).isEqualTo(1);
    }

    @Test
    void transferAllKeepsVectors() {
        ledger.mint(CAUSE, alice, DIAMOND, 4, aliceOrigin);
        ledger.transfer(CAUSE, alice, chest, DIAMOND, 4);
        val otherHalf = Holder.block("world", 2, 64, 1);

        ledger.transferAll(CAUSE, chest, otherHalf);

        assertThat(ledger.getPools(chest)).isEmpty();
        assertThat(ledger.getCount(otherHalf, DIAMOND)).isEqualTo(4);
        assertThat(ledger.pool(otherHalf, DIAMOND).getCustody()).isEqualTo(TaintVector.of(aliceOrigin));
    }
}

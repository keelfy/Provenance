package dev.keelfy.provenance.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import lombok.val;

class SettlementTest {

    private static final ItemKey DIAMOND = new ItemKey("minecraft:diamond");
    private static final ItemKey STICK = new ItemKey("minecraft:stick");
    private static final ItemKey PICKAXE = new ItemKey("minecraft:diamond_pickaxe");

    private final UUID aliceId = UUID.randomUUID();
    private final HolderId alice = HolderId.player(aliceId);
    private final OriginId aliceOrigin = OriginId.player(aliceId);
    private final HolderId chest = HolderId.block("world", 1, 64, 1);
    private final Ledger ledger = new Ledger();

    @Test
    void oppositeChangesBecomeTransfers() {
        ledger.mint(alice, DIAMOND, 10, aliceOrigin);

        Settlement.container(ledger, alice, Map.of(DIAMOND, 4L), chest, Map.of(DIAMOND, 6L), OriginId.UNKNOWN);

        assertThat(ledger.getCount(alice, DIAMOND)).isEqualTo(4);
        assertThat(ledger.pool(chest, DIAMOND).getOrigin()).isEqualTo(TaintVector.of(aliceOrigin));
        assertThat(ledger.pool(chest, DIAMOND).getCustody()).isEqualTo(TaintVector.of(aliceOrigin));
    }

    @Test
    void unmatchedChangesAreReconciledWithGivenOrigin() {
        Settlement.container(ledger, alice, Map.of(DIAMOND, 3L), chest, Map.of(), OriginId.CREATIVE);

        assertThat(ledger.pool(alice, DIAMOND).getOrigin()).isEqualTo(TaintVector.of(OriginId.CREATIVE));
    }

    @Test
    void workstationOutputInheritsInputOrigin() {
        ledger.mint(alice, DIAMOND, 3, aliceOrigin);
        ledger.mint(alice, STICK, 2, OriginId.UNKNOWN);

        Settlement.workstation(ledger, alice, Map.of(PICKAXE, 1L), OriginId.UNKNOWN);

        assertThat(ledger.getPools(alice)).containsOnlyKeys(PICKAXE);
        assertThat(ledger.pool(alice, PICKAXE).getOrigin().share(aliceOrigin)).isCloseTo(0.6, within(1e-9));
    }

    @Test
    void workstationOutputWithoutInputIsMintedNotCrafted() {
        Settlement.workstation(ledger, alice, Map.of(DIAMOND, 3L), OriginId.CREATIVE);

        assertThat(ledger.pool(alice, DIAMOND).getOrigin()).isEqualTo(TaintVector.of(OriginId.CREATIVE));
    }

    @Test
    void workstationWithoutOutputBurns() {
        ledger.mint(alice, DIAMOND, 3, aliceOrigin);

        Settlement.workstation(ledger, alice, Map.of(DIAMOND, 1L), OriginId.UNKNOWN);

        assertThat(ledger.getCount(alice, DIAMOND)).isEqualTo(1);
    }

    @Test
    void transferAllKeepsVectors() {
        ledger.mint(alice, DIAMOND, 4, aliceOrigin);
        ledger.transfer(alice, chest, DIAMOND, 4);
        val otherHalf = HolderId.block("world", 2, 64, 1);

        ledger.transferAll(chest, otherHalf);

        assertThat(ledger.getPools(chest)).isEmpty();
        assertThat(ledger.getCount(otherHalf, DIAMOND)).isEqualTo(4);
        assertThat(ledger.pool(otherHalf, DIAMOND).getCustody()).isEqualTo(TaintVector.of(aliceOrigin));
    }
}

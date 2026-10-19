package dev.keelfy.provenance.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.keelfy.provenance.core.analysis.Correlation;
import dev.keelfy.provenance.core.analysis.Link;
import dev.keelfy.provenance.core.model.Cause;
import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.ItemKey;
import dev.keelfy.provenance.core.model.OriginId;
import dev.keelfy.provenance.core.taint.TaintVector;
import java.util.Map;
import java.util.UUID;
import lombok.val;
import org.junit.jupiter.api.Test;

/** Acceptance scenarios from ARCHITECTURE.md §8, expressed against the core ledger. */
class LedgerTest {

    private static final Cause CAUSE = Cause.CONTAINER;

    private static final ItemKey DIAMOND = new ItemKey("minecraft:diamond");
    private static final ItemKey DIAMOND_BLOCK = new ItemKey("minecraft:diamond_block");

    private final UUID aliceId = UUID.randomUUID();
    private final UUID bobId = UUID.randomUUID();
    private final UUID carolId = UUID.randomUUID();
    private final Holder alice = Holder.player(aliceId);
    private final Holder bob = Holder.player(bobId);
    private final Holder carol = Holder.player(carolId);
    private final OriginId aliceOrigin = OriginId.player(aliceId);
    private final OriginId bobOrigin = OriginId.player(bobId);
    private final OriginId carolOrigin = OriginId.player(carolId);
    private final Holder chest = Holder.block("world", 0, 64, 0);
    private final Holder droppedItem = Holder.entity(UUID.randomUUID());

    private final Ledger ledger = new Ledger();
    private final Correlation correlation = new Correlation(ledger);

    @Test
    void transferThroughChestRecordsFlowAndWealth() {
        ledger.mint(CAUSE, alice, DIAMOND, 10, aliceOrigin);
        ledger.transfer(CAUSE, alice, chest, DIAMOND, 10);
        ledger.transfer(CAUSE, chest, bob, DIAMOND, 5);

        assertThat(correlation.wealth(bobOrigin)).containsOnlyKeys(aliceOrigin);
        assertThat(correlation.wealth(bobOrigin).get(aliceOrigin)).isCloseTo(5, within(1e-9));
        assertThat(ledger.getFlows().flow(aliceOrigin, bobOrigin, DIAMOND)).isCloseTo(5, within(1e-9));
        // Alice still has custody of what is left in the chest.
        assertThat(correlation.wealth(aliceOrigin).get(aliceOrigin)).isCloseTo(5, within(1e-9));
    }

    @Test
    void stackMovesTogetherWithItsContents() {
        val shulker = new ItemKey("minecraft:shulker_box");
        ledger.mint(CAUSE, alice, DIAMOND, 64, aliceOrigin);
        ledger.mint(CAUSE, alice, shulker, 1, OriginId.UNKNOWN);

        ledger.transfer(CAUSE, alice, chest, Map.of(shulker, 1L, DIAMOND, 64L));

        assertThat(ledger.getCount(chest, DIAMOND)).isEqualTo(64);
        assertThat(ledger.pool(chest, DIAMOND).getOrigin().shares()).containsOnlyKeys(aliceOrigin);
        assertThat(ledger.getCount(alice, DIAMOND)).isZero();
    }

    @Test
    void ownProductionDilutesReceivedShare() {
        transferThroughChestRecordsFlowAndWealth();
        ledger.mint(CAUSE, bob, DIAMOND, 5, bobOrigin);

        val bobDiamonds = ledger.pool(bob, DIAMOND).getOrigin();
        assertThat(bobDiamonds.share(aliceOrigin)).isCloseTo(0.5, within(1e-9));
        assertThat(bobDiamonds.share(bobOrigin)).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void craftingDoesNotLaunderOrigin() {
        ownProductionDilutesReceivedShare();
        ledger.convert(CAUSE, bob, Map.of(DIAMOND, 9L), Map.of(DIAMOND_BLOCK, 1L));
        ledger.transfer(CAUSE, bob, droppedItem, DIAMOND_BLOCK, 1);
        ledger.transfer(CAUSE, droppedItem, carol, DIAMOND_BLOCK, 1);

        assertThat(ledger.getCount(bob, DIAMOND)).isEqualTo(1);
        assertThat(ledger.pool(carol, DIAMOND_BLOCK).getOrigin().share(aliceOrigin))
                .isCloseTo(0.5, within(1e-9));
        // Custody chain through the dropped entity credits Bob, not Alice.
        assertThat(ledger.getFlows().flow(bobOrigin, carolOrigin, DIAMOND_BLOCK))
                .isCloseTo(1, within(1e-9));
        assertThat(ledger.getFlows().flow(aliceOrigin, carolOrigin)).isZero();
    }

    @Test
    void reconcileMintsUnknownAndBurnsMissing() {
        ledger.mint(CAUSE, alice, DIAMOND, 3, aliceOrigin);
        ledger.reconcile(CAUSE, alice, Map.of(DIAMOND, 5L, DIAMOND_BLOCK, 1L));

        assertThat(ledger.getCount(alice, DIAMOND)).isEqualTo(5);
        assertThat(ledger.pool(alice, DIAMOND).getOrigin().share(OriginId.UNKNOWN))
                .isCloseTo(0.4, within(1e-9));
        assertThat(ledger.pool(alice, DIAMOND_BLOCK).getOrigin()).isEqualTo(TaintVector.of(OriginId.UNKNOWN));

        ledger.reconcile(CAUSE, alice, Map.of());
        assertThat(ledger.getHolders()).doesNotContain(alice);
    }

    @Test
    void transferConservesCountsAndMintsShortfallAsUnknown() {
        ledger.mint(CAUSE, alice, DIAMOND, 4, aliceOrigin);
        ledger.transfer(CAUSE, alice, chest, DIAMOND, 6);

        assertThat(ledger.getCount(alice, DIAMOND)).isZero();
        assertThat(ledger.getCount(chest, DIAMOND)).isEqualTo(6);
        assertThat(ledger.pool(chest, DIAMOND).getOrigin().share(OriginId.UNKNOWN))
                .isCloseTo(2.0 / 6, within(1e-9));
    }

    @Test
    void sharedChestMakesContributorsSimilar() {
        ledger.mint(CAUSE, alice, DIAMOND, 10, aliceOrigin);
        ledger.transfer(CAUSE, alice, chest, DIAMOND, 10);
        ledger.transfer(CAUSE, chest, bob, DIAMOND, 5);
        ledger.transfer(CAUSE, chest, carol, DIAMOND, 5);

        assertThat(correlation.similarity(bobOrigin, carolOrigin)).isCloseTo(1.0, within(1e-9));
        assertThat(correlation.links(aliceOrigin))
                .extracting(Link::other)
                .containsExactlyInAnyOrder(bobOrigin, carolOrigin);
    }
}

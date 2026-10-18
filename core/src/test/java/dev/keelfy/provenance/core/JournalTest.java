package dev.keelfy.provenance.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.val;
import org.junit.jupiter.api.Test;

/** The event stream alone must reproduce the ledger state. */
class JournalTest {

    private static final ItemKey DIAMOND = new ItemKey("minecraft:diamond");
    private static final ItemKey STICK = new ItemKey("minecraft:stick");
    private static final ItemKey PICKAXE = new ItemKey("minecraft:diamond_pickaxe");

    private final UUID aliceId = UUID.randomUUID();
    private final UUID bobId = UUID.randomUUID();
    private final HolderId alice = HolderId.player(aliceId);
    private final HolderId bob = HolderId.player(bobId);
    private final HolderId chest = HolderId.block("world", 0, 64, 0);
    private final HolderId movedChest = HolderId.block("world", 1, 64, 0);

    private long now;
    private final Ledger ledger = new Ledger(() -> ++now);

    @Test
    void replayReproducesState() {
        ledger.mint(Cause.MINE, alice, DIAMOND, 100, OriginId.player(aliceId));
        ledger.mint(Cause.LOOT, alice, STICK, 2, OriginId.LOOT);
        ledger.transfer(Cause.CONTAINER, alice, chest, DIAMOND, 100);
        ledger.transfer(Cause.CONTAINER, chest, bob, DIAMOND, 130);
        ledger.convert(Cause.CRAFT, bob, Map.of(DIAMOND, 3L, STICK, 2L), Map.of(PICKAXE, 1L));
        ledger.transferAll(Cause.CONTAINER, chest, movedChest);
        ledger.reconcile(Cause.RECONCILE, bob, Map.of(DIAMOND, 200L), OriginId.UNKNOWN);
        ledger.burn(Cause.DROP, bob, DIAMOND, 5);
        ledger.clear(Cause.CONTAINER, movedChest);

        val changes = ledger.drainChanges();
        val rebuilt = new Ledger();
        changes.events().forEach(rebuilt::replay);

        assertSameState(rebuilt);
        assertThat(rebuilt.drainChanges().events()).isEmpty();
    }

    @Test
    void sequenceIsGaplessAndCarriesTimeAndCause() {
        ledger.mint(Cause.MINE, alice, DIAMOND, 1, OriginId.player(aliceId));
        ledger.transfer(Cause.CONTAINER, alice, chest, DIAMOND, 3);

        val events = ledger.drainChanges().events();

        // Transfer of 3 with 1 tracked splits into a real transfer and an unknown mint.
        assertThat(events).extracting(Envelope::seq).containsExactly(1L, 2L, 3L);
        assertThat(events).extracting(Envelope::time).containsExactly(1L, 2L, 3L);
        assertThat(events).extracting(e -> e.cause().key()).containsExactly("mine", "container", "container");
        assertThat(events.get(2).event()).isEqualTo(new LedgerEvent.Minted(chest, DIAMOND, 2, OriginId.UNKNOWN));
    }

    @Test
    void transferToPlayerRecordsWhoseItemsWereTaken() {
        ledger.mint(Cause.MINE, alice, DIAMOND, 10, OriginId.player(aliceId));
        ledger.transfer(Cause.CONTAINER, alice, chest, DIAMOND, 10);
        ledger.transfer(Cause.CONTAINER, chest, bob, DIAMOND, 5);

        val events = ledger.drainChanges().events();

        // alice -> chest: receiver is not a player, nothing to attribute.
        assertThat(((LedgerEvent.Transferred) events.get(1).event()).custody()).isEqualTo(TaintVector.EMPTY);
        assertThat(((LedgerEvent.Transferred) events.get(2).event()).custody())
                .isEqualTo(TaintVector.of(OriginId.player(aliceId)));
    }

    @Test
    void restoredSnapshotPlusTailEqualsFullReplay() {
        ledger.mint(Cause.MINE, alice, DIAMOND, 10, OriginId.player(aliceId));
        ledger.transfer(Cause.CONTAINER, alice, chest, DIAMOND, 10);
        val head = ledger.drainChanges();

        ledger.transfer(Cause.CONTAINER, chest, bob, DIAMOND, 4);
        val tail = ledger.drainChanges();

        val rebuilt = new Ledger();
        head.holders().forEach((holder, rows) -> rows.forEach(row -> rebuilt.restore(holder, row)));
        head.flows().forEach(rebuilt::restore);
        rebuilt.restoreSeq(head.lastSeq());
        tail.events().forEach(rebuilt::replay);

        assertSameState(rebuilt);
        assertThat(rebuilt.drainChanges().lastSeq()).isEqualTo(tail.lastSeq());
    }

    @Test
    void repeatedAutomationMovesAreJournaledOnce() {
        ledger.mint(Cause.MINE, chest, DIAMOND, 6, OriginId.player(aliceId));
        ledger.mint(Cause.MINE, chest, DIAMOND, 4, OriginId.player(bobId));
        for (int i = 0; i < 7; i++) {
            ledger.transfer(Cause.HOPPER, chest, movedChest, DIAMOND, 1);
        }

        val events = ledger.drainChanges().events();
        val rebuilt = new Ledger();
        events.forEach(rebuilt::replay);

        assertThat(events).hasSize(3);
        assertThat(events.get(2).event())
                .isEqualTo(new LedgerEvent.Transferred(chest, movedChest, DIAMOND, 7, TaintVector.EMPTY));
        // One 7-item move instead of seven 1-item moves differs from the live state only by float noise in shares.
        for (val holder : List.of(chest, movedChest)) {
            val live = ledger.pool(holder, DIAMOND);
            val twin = rebuilt.pool(holder, DIAMOND);
            assertThat(twin.getCount()).isEqualTo(live.getCount());
            live.getOrigin()
                    .shares()
                    .forEach((origin, share) ->
                            assertThat(twin.getOrigin().share(origin)).isCloseTo(share, within(1e-9)));
        }
    }

    @Test
    void automationMovesStaySeparateWhenMergingIsOff() {
        val plain = new Ledger(() -> ++now, false);
        plain.mint(Cause.MINE, chest, DIAMOND, 10, OriginId.player(aliceId));
        plain.transfer(Cause.HOPPER, chest, movedChest, DIAMOND, 1);
        plain.transfer(Cause.HOPPER, chest, movedChest, DIAMOND, 1);

        assertThat(plain.drainChanges().events()).hasSize(3);
    }

    @Test
    void playerMovesAreNeverMerged() {
        ledger.mint(Cause.MINE, chest, DIAMOND, 10, OriginId.player(aliceId));
        ledger.transfer(Cause.CONTAINER, chest, bob, DIAMOND, 1);
        ledger.transfer(Cause.CONTAINER, chest, bob, DIAMOND, 1);

        assertThat(ledger.drainChanges().events()).hasSize(3);
    }

    private void assertSameState(Ledger other) {
        assertThat(other.getHolders()).isEqualTo(ledger.getHolders());
        for (val holder : new HashSet<>(ledger.getHolders())) {
            assertThat(other.getPools(holder).keySet())
                    .isEqualTo(ledger.getPools(holder).keySet());
            ledger.getPools(holder).forEach((item, pool) -> {
                val twin = other.pool(holder, item);
                assertThat(twin.getCount()).isEqualTo(pool.getCount());
                assertThat(twin.getOrigin()).isEqualTo(pool.getOrigin());
                assertThat(twin.getCustody()).isEqualTo(pool.getCustody());
            });
        }
        assertThat(other.getFlows().getEdges()).isEqualTo(ledger.getFlows().getEdges());
    }
}

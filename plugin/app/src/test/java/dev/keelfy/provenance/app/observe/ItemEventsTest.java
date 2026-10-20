package dev.keelfy.provenance.app.observe;

import static org.assertj.core.api.Assertions.assertThat;

import dev.keelfy.provenance.app.Fakes;
import dev.keelfy.provenance.app.inventory.InventoryReconciler;
import dev.keelfy.provenance.app.inventory.ViewTarget;
import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.core.event.LedgerEvent;
import dev.keelfy.provenance.core.model.Cause;
import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.ItemKey;
import dev.keelfy.provenance.core.model.OriginId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.val;
import org.junit.jupiter.api.Test;

class ItemEventsTest {

    private static final ItemKey DIAMOND = new ItemKey("minecraft:diamond");

    private final Fakes.ManualScheduler scheduler = new Fakes.ManualScheduler();
    private final Ledger ledger = new Ledger(() -> 0);
    private final Fakes.Player alice = new Fakes.Player();
    private final InventoryReconciler reconciler =
            new InventoryReconciler(scheduler, Fakes.directory(alice), ledger, Fakes.settings());
    private final ItemEvents items = new ItemEvents(ledger, reconciler, scheduler);
    private final Holder entity = Holder.entity(UUID.randomUUID());

    @Test
    void droppingFirstAlignsThePlayerWithWhatTheyHadBeforeTheDrop() {
        alice.items.put(DIAMOND, 1L); // what is left after throwing 2

        items.dropped(alice, entity, Map.of(DIAMOND, 2L));

        assertThat(ledger.getCount(entity, DIAMOND)).isEqualTo(2);
        assertThat(ledger.getCount(alice.holder(), DIAMOND)).isEqualTo(1);
        assertThat(ledger.pool(entity, DIAMOND).getOrigin().shares()).containsOnlyKeys(OriginId.UNKNOWN);
    }

    @Test
    void stackThrownOutOfAChestIsBookedAsChestToPlayerToEntity() {
        val chest = Holder.block("world", 0, 64, 0);
        ledger.mint(Cause.PLACE, chest, DIAMOND, 10, OriginId.WORLD);
        val inChest = new HashMap<ItemKey, Long>(Map.of(DIAMOND, 10L));
        reconciler.track(alice, ViewTarget.container(chest, () -> new HashMap<>(inChest)));

        inChest.put(DIAMOND, 6L);
        items.dropped(alice, entity, Map.of(DIAMOND, 4L)); // thrown straight from the chest slot, same tick
        scheduler.tick();

        assertThat(ledger.getCount(chest, DIAMOND)).isEqualTo(6);
        assertThat(ledger.getCount(alice.holder(), DIAMOND)).isZero();
        assertThat(ledger.getCount(entity, DIAMOND)).isEqualTo(4);
        assertThat(ledger.pool(entity, DIAMOND).getOrigin().shares()).containsOnlyKeys(OriginId.WORLD);
    }

    @Test
    void creativePlayersMintCreativeItems() {
        alice.creative = true;
        alice.items.put(DIAMOND, 1L);

        items.dropped(alice, entity, Map.of(DIAMOND, 1L));

        assertThat(ledger.pool(entity, DIAMOND).getOrigin().shares()).containsOnlyKeys(OriginId.CREATIVE);
    }

    @Test
    void pickingUpAlignsThePlayerFirst() {
        ledger.mint(Cause.DROP, entity, DIAMOND, 2, OriginId.WORLD);
        alice.items.put(DIAMOND, 1L); // the server adds the picked-up items after the event

        items.pickedUp(entity, alice, Map.of(DIAMOND, 2L));

        assertThat(ledger.getCount(alice.holder(), DIAMOND)).isEqualTo(3);
        assertThat(ledger.getCount(entity, DIAMOND)).isZero();
    }

    @Test
    void mergingMovesOnlyWhatFitted() {
        val target = Holder.entity(UUID.randomUUID());
        ledger.mint(Cause.DROP, entity, DIAMOND, 5, OriginId.WORLD);

        items.merged(entity, target, Map.of(DIAMOND, 3L));
        items.merged(entity, target, Map.of());

        assertThat(ledger.getCount(entity, DIAMOND)).isEqualTo(2);
        assertThat(ledger.getCount(target, DIAMOND)).isEqualTo(3);
    }

    @Test
    void aRemovedFrameKeepsItsPoolOneMoreTick() {
        ledger.mint(Cause.DROP, entity, DIAMOND, 1, OriginId.WORLD);

        items.removed(entity, Cause.DROP, true);
        assertThat(ledger.getCount(entity, DIAMOND)).isEqualTo(1);

        scheduler.tick();
        assertThat(ledger.getCount(entity, DIAMOND)).isZero();
    }

    @Test
    void otherEntitiesAreClearedAtOnce() {
        ledger.mint(Cause.DROP, entity, DIAMOND, 1, OriginId.WORLD);

        items.removed(entity, Cause.DROP, false);

        assertThat(ledger.getCount(entity, DIAMOND)).isZero();
    }

    @Test
    void eatingBurnsOneItemBecauseItWasConsumed() {
        alice.items.put(DIAMOND, 3L); // the server shrinks the stack after the event

        items.consumed(alice, DIAMOND);

        assertThat(ledger.getCount(alice.holder(), DIAMOND)).isEqualTo(2);
        assertThat(ledger.drainChanges().events()).anySatisfy(e -> {
            assertThat(e.cause()).isEqualTo(Cause.CONSUME);
            assertThat(e.event()).isInstanceOf(LedgerEvent.Burned.class);
        });
    }

    @Test
    void creativePlayersConsumeNothing() {
        alice.creative = true;
        alice.items.put(DIAMOND, 3L);

        items.consumed(alice, DIAMOND);

        assertThat(ledger.drainChanges().events()).isEmpty();
    }

    @Test
    void breakingAndThrowingBurnOneItemWithTheirOwnCause() {
        alice.items.put(DIAMOND, 2L);

        items.broken(alice, DIAMOND);
        alice.items.put(DIAMOND, 1L); // the server shrinks the stack
        items.launched(alice, DIAMOND);

        assertThat(ledger.getCount(alice.holder(), DIAMOND)).isZero();
        assertThat(ledger.drainChanges().events())
                .filteredOn(e -> e.event() instanceof LedgerEvent.Burned)
                .extracting(e -> e.cause())
                .containsExactly(Cause.BREAK, Cause.SHOOT);
    }

    @Test
    void aGroundItemIsClearedWithTheReasonItWentAway() {
        ledger.mint(Cause.DROP, entity, DIAMOND, 1, OriginId.WORLD);
        ledger.drainChanges();

        items.removed(entity, Cause.DESTROY, false);

        assertThat(ledger.drainChanges().events())
                .singleElement()
                .satisfies(e -> assertThat(e.cause()).isEqualTo(Cause.DESTROY));
    }
}

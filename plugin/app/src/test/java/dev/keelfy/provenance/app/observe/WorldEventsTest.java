package dev.keelfy.provenance.app.observe;

import static org.assertj.core.api.Assertions.assertThat;

import dev.keelfy.provenance.app.Fakes;
import dev.keelfy.provenance.app.inventory.InventoryReconciler;
import dev.keelfy.provenance.app.world.ItemDrop;
import dev.keelfy.provenance.app.world.SpawnContexts;
import dev.keelfy.provenance.app.world.WorldPos;
import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.core.event.LedgerEvent;
import dev.keelfy.provenance.core.model.Cause;
import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.ItemKey;
import dev.keelfy.provenance.core.model.OriginId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.val;
import org.junit.jupiter.api.Test;

class WorldEventsTest {

    private static final ItemKey BONE = new ItemKey("minecraft:bone");
    private static final ItemKey DIAMOND = new ItemKey("minecraft:diamond");
    private static final WorldPos AT = new WorldPos("world", 0, 64, 0);

    private final Fakes.ManualScheduler scheduler = new Fakes.ManualScheduler();
    private final Ledger ledger = new Ledger(() -> 0);
    private final SpawnContexts contexts = new SpawnContexts(scheduler);
    private final WorldEvents world = new WorldEvents(
            Fakes.settings(),
            ledger,
            contexts,
            new InventoryReconciler(scheduler, Fakes.directory(), ledger, Fakes.settings()),
            scheduler);

    private final Holder chest = Holder.block("world", 0, 64, 0).described("minecraft:chest");
    private final Holder otherHalf = Holder.block("world", 1, 64, 0).described("minecraft:chest");
    private final OriginId alice = OriginId.player(UUID.randomUUID());

    @Test
    void killedMobMintsOnlyWhatItDoesNotAlreadyCarry() {
        val zombie = Holder.entity(UUID.randomUUID());
        ledger.mint(Cause.KILL, zombie, BONE, 1, OriginId.MOB);

        world.mobKilled(zombie, AT, alice, List.of(Map.of(BONE, 3L)));

        assertThat(ledger.getCount(zombie, BONE)).isEqualTo(3);
        assertThat(ledger.pool(zombie, BONE).getOrigin().shares()).containsKeys(OriginId.MOB, alice);
    }

    @Test
    void mobKilledByTheWorldDropsMobLoot() {
        val zombie = Holder.entity(UUID.randomUUID());

        world.mobKilled(zombie, AT, null, List.of(Map.of(BONE, 2L)));

        assertThat(ledger.pool(zombie, BONE).getOrigin().shares()).containsOnlyKeys(OriginId.MOB);
    }

    @Test
    void dropsOfABrokenContainerKeepItsHistory() {
        ledger.mint(Cause.MINE, chest, DIAMOND, 5, alice);
        val drop = new ItemDrop(Holder.entity(UUID.randomUUID()), AT, DIAMOND, Map.of(DIAMOND, 5L));

        world.containerDestroyed(chest, Map.of(DIAMOND, 5L), AT, OriginId.WORLD, () -> null);
        world.blockDrops(OriginId.UNKNOWN, List.of(drop));

        assertThat(ledger.getCount(drop.entity(), DIAMOND)).isEqualTo(5);
        assertThat(ledger.pool(drop.entity(), DIAMOND).getOrigin().shares()).containsOnlyKeys(alice);
    }

    @Test
    void brokenContainerWithoutSurvivorIsClearedAfterTheSpill() {
        ledger.mint(Cause.MINE, chest, DIAMOND, 5, alice);

        world.containerDestroyed(chest, Map.of(DIAMOND, 5L), AT, OriginId.WORLD, () -> null);
        assertThat(ledger.getCount(chest, DIAMOND)).isEqualTo(5);

        scheduler.tick();
        assertThat(ledger.getCount(chest, DIAMOND)).isZero();
    }

    @Test
    void survivingHalfOfADoubleChestInheritsWhatWasLeft() {
        ledger.mint(Cause.MINE, chest, DIAMOND, 5, alice);

        world.containerDestroyed(
                chest,
                Map.of(DIAMOND, 5L),
                AT,
                OriginId.WORLD,
                () -> new WorldEvents.Remains(otherHalf, Map.of(DIAMOND, 5L)));
        scheduler.tick();

        assertThat(ledger.getCount(chest, DIAMOND)).isZero();
        assertThat(ledger.getCount(otherHalf, DIAMOND)).isEqualTo(5);
    }

    @Test
    void placedHalvesJoinTheCanonicalHolder() {
        ledger.mint(Cause.CONTAINER, otherHalf, DIAMOND, 2, alice);

        world.containerPlaced(() -> new WorldEvents.Halves(chest, List.of(chest, otherHalf)));
        assertThat(ledger.getCount(chest, DIAMOND)).isZero();

        scheduler.tick();
        assertThat(ledger.getCount(chest, DIAMOND)).isEqualTo(2);
        assertThat(ledger.getCount(otherHalf, DIAMOND)).isZero();
    }

    @Test
    void unattributedItemsAreTheWorldsUnlessSomebodyThrewThem() {
        val thrown = new ItemDrop(Holder.entity(UUID.randomUUID()), AT, DIAMOND, Map.of(DIAMOND, 1L));
        val fallen = new ItemDrop(Holder.entity(UUID.randomUUID()), AT, DIAMOND, Map.of(DIAMOND, 1L));

        world.itemSpawned(thrown, true);
        world.itemSpawned(fallen, false);

        assertThat(ledger.getCount(thrown.entity(), DIAMOND)).isZero();
        assertThat(ledger.pool(fallen.entity(), DIAMOND).getOrigin().shares()).containsOnlyKeys(OriginId.WORLD);
    }

    @Test
    void anItemEntityIsAttributedOnce() {
        val drop = new ItemDrop(Holder.entity(UUID.randomUUID()), AT, DIAMOND, Map.of(DIAMOND, 1L));

        world.fished(alice, drop);
        world.fished(OriginId.WORLD, drop);

        assertThat(ledger.getCount(drop.entity(), DIAMOND)).isEqualTo(1);
        assertThat(ledger.pool(drop.entity(), DIAMOND).getOrigin().shares()).containsOnlyKeys(alice);
    }

    @Test
    void lootGoesIntoContainersButNotIntoPlayers() {
        val player = Holder.player(UUID.randomUUID());

        world.looted(chest, List.of(new ItemAmount(DIAMOND, 2), new ItemAmount(DIAMOND, 1)));
        world.looted(player, List.of(new ItemAmount(DIAMOND, 9)));
        world.looted(null, List.of(new ItemAmount(DIAMOND, 9)));

        assertThat(ledger.getCount(chest, DIAMOND)).isEqualTo(3);
        assertThat(ledger.getCount(player, DIAMOND)).isZero();
        assertThat(ledger.getHolders()).containsExactly(chest);
    }

    @Test
    void clickingATileTracksItsItemsForTheSpill() {
        val player = new Fakes.Player();
        ledger.mint(Cause.CONTAINER, chest, DIAMOND, 1, alice);
        val drop = new ItemDrop(Holder.entity(UUID.randomUUID()), AT, DIAMOND, Map.of(DIAMOND, 1L));

        world.tileClicked(player, chest, AT, Set.of(DIAMOND), () -> Map.of(DIAMOND, 1L));
        world.itemSpawned(drop, false);

        assertThat(ledger.pool(drop.entity(), DIAMOND).getOrigin().shares()).containsOnlyKeys(alice);
    }

    @Test
    void aPlacedBlockBurnsTheItemAndShulkerContentsMoveToTheBlock() {
        val player = new Fakes.Player();
        player.items.put(DIAMOND, 4L); // one of them is the block; the server shrinks the stack after the event
        player.items.put(BONE, 2L);

        world.blockPlaced(player, chest, DIAMOND, Map.of(BONE, 2L));

        assertThat(ledger.getCount(player.holder(), DIAMOND)).isEqualTo(3);
        assertThat(ledger.getCount(chest, BONE)).isEqualTo(2);
        assertThat(ledger.drainChanges().events()).anySatisfy(e -> {
            assertThat(e.cause()).isEqualTo(Cause.PLACE);
            assertThat(e.event()).isInstanceOf(LedgerEvent.Burned.class);
        });
    }
}

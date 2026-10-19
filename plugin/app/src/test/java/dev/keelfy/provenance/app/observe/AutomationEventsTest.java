package dev.keelfy.provenance.app.observe;

import static org.assertj.core.api.Assertions.assertThat;

import dev.keelfy.provenance.app.Fakes;
import dev.keelfy.provenance.app.world.SpawnContexts;
import dev.keelfy.provenance.app.world.WorldPos;
import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.core.model.Cause;
import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.ItemKey;
import dev.keelfy.provenance.core.model.OriginId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.val;
import org.junit.jupiter.api.Test;

class AutomationEventsTest {

    private static final ItemKey POTION = new ItemKey("minecraft:potion");
    private static final ItemKey AWKWARD = new ItemKey("minecraft:awkward_potion");
    private static final ItemKey WART = new ItemKey("minecraft:nether_wart");
    private static final ItemKey PLANK = new ItemKey("minecraft:oak_planks");
    private static final ItemKey STICK = new ItemKey("minecraft:stick");

    private final Ledger ledger = new Ledger(() -> 0);
    private final SpawnContexts contexts = new SpawnContexts(new Fakes.ManualScheduler());
    private final AutomationEvents automation = new AutomationEvents(Fakes.settings(), ledger, contexts);
    private final Holder stand = Holder.block("world", 0, 64, 0);
    private final OriginId alice = OriginId.player(UUID.randomUUID());

    @Test
    void brewingConsumesTheIngredientWhenSomethingWasBrewed() {
        ledger.mint(Cause.CONTAINER, stand, POTION, 1, alice);
        ledger.mint(Cause.CONTAINER, stand, WART, 1, alice);

        automation.brewed(stand, Map.of(POTION, 1L), Map.of(AWKWARD, 1L), WART);

        assertThat(ledger.getCount(stand, WART)).isZero();
        assertThat(ledger.getCount(stand, AWKWARD)).isEqualTo(1);
    }

    @Test
    void burningFuelRemovesOneItemBecauseOfFuel() {
        ledger.mint(Cause.CONTAINER, stand, PLANK, 2, alice);

        automation.fuelBurned(stand, PLANK);

        assertThat(ledger.getCount(stand, PLANK)).isEqualTo(1);
        assertThat(ledger.drainChanges().events().getLast().cause()).isEqualTo(Cause.FUEL);
    }

    @Test
    void brewingNothingKeepsTheIngredient() {
        ledger.mint(Cause.CONTAINER, stand, WART, 1, alice);

        automation.brewed(stand, Map.of(), Map.of(), WART);

        assertThat(ledger.getCount(stand, WART)).isEqualTo(1);
    }

    @Test
    void crafterTakesOneOfEachFilledSlot() {
        ledger.mint(Cause.CONTAINER, stand, PLANK, 2, alice);

        automation.crafted(stand, List.of(PLANK, PLANK), Map.of(STICK, 4L));

        assertThat(ledger.getCount(stand, PLANK)).isZero();
        assertThat(ledger.getCount(stand, STICK)).isEqualTo(4);
    }

    @Test
    void hoppersMoveItemsBetweenHolders() {
        val hopper = Holder.block("world", 0, 63, 0);
        ledger.mint(Cause.CONTAINER, stand, PLANK, 2, alice);

        automation.moved(stand, hopper, Map.of(PLANK, 1L));

        assertThat(ledger.getCount(hopper, PLANK)).isEqualTo(1);
    }

    @Test
    void cookedItemsCarryTheCampfiresPoolWhenTheyPopOut() {
        ledger.mint(Cause.CONTAINER, stand, PLANK, 1, alice);
        val center = new WorldPos("world", 0.5, 64.5, 0.5);

        automation.cooked(stand, center, PLANK, STICK, 1);

        assertThat(contexts.find(center, STICK)).isNotNull();
        assertThat(ledger.getCount(stand, STICK)).isEqualTo(1);
    }
}

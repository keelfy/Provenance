package dev.keelfy.provenance.app.observe;

import dev.keelfy.provenance.app.Settings;
import dev.keelfy.provenance.app.world.SpawnContexts;
import dev.keelfy.provenance.app.world.SpawnSource;
import dev.keelfy.provenance.app.world.WorldPos;
import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.core.model.Cause;
import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.ItemKey;
import dev.keelfy.provenance.core.model.OriginId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.jspecify.annotations.Nullable;

/**
 * Item movement and conversion without a player in the loop: hoppers, droppers, hopper minecarts, furnaces, brewing
 * stands, campfires and crafters. The platform reports what happened; this decides what it means for the ledger.
 *
 * @author keelfy
 */
@RequiredArgsConstructor
public final class AutomationEvents {

    private final Settings settings;
    private final Ledger ledger;
    private final SpawnContexts contexts;

    /** A hopper or dropper moved items between two holders, or a hopper picked an item entity up. */
    public void moved(Holder from, Holder to, Map<ItemKey, Long> items) {
        ledger.transfer(Cause.HOPPER, from, to, items);
    }

    public void smelted(Holder block, ItemKey input, ItemKey output, long outputAmount) {
        ledger.convert(Cause.SMELT, block, Map.of(input, 1L), Map.of(output, outputAmount));
    }

    /** A furnace or brewing stand burned one piece of fuel. */
    public void fuelBurned(Holder block, ItemKey fuel) {
        ledger.burn(Cause.FUEL, block, fuel, 1);
    }

    /**
     * Each brewed bottle becomes its result and takes on the ingredient's origin; untouched bottles stay as they are.
     *
     * @param inputs the bottles that changed
     * @param outputs what they became
     * @param ingredient the ingredient used up, if any
     */
    public void brewed(
            Holder block, Map<ItemKey, Long> inputs, Map<ItemKey, Long> outputs, @Nullable ItemKey ingredient) {
        val consumed = new HashMap<>(inputs);
        if (ingredient != null && !outputs.isEmpty()) {
            consumed.merge(ingredient, 1L, Long::sum);
        }
        ledger.convert(Cause.CRAFT, block, consumed, outputs);
    }

    /** A campfire drops what it has cooked; the dropped item carries the pool of the campfire. */
    public void cooked(Holder block, WorldPos center, ItemKey input, ItemKey output, long outputAmount) {
        smelted(block, input, output, outputAmount);
        contexts.add(center, settings.ejectRadius(), new SpawnSource(block, OriginId.UNKNOWN, Cause.SMELT), output);
    }

    /**
     * One of each filled slot goes into the result; the crafter then ejects it as a dispense.
     *
     * @param filledSlots the item of every filled slot
     */
    public void crafted(Holder block, List<ItemKey> filledSlots, Map<ItemKey, Long> result) {
        val inputs = new HashMap<ItemKey, Long>();
        filledSlots.forEach(key -> inputs.merge(key, 1L, Long::sum));
        ledger.convert(Cause.CRAFT, block, inputs, result);
    }
}

package dev.keelfy.provenance.paper.listener;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import dev.keelfy.provenance.Settings;
import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.core.model.Cause;
import dev.keelfy.provenance.core.model.ItemKey;
import dev.keelfy.provenance.core.model.OriginId;
import dev.keelfy.provenance.paper.Holders;
import dev.keelfy.provenance.paper.ItemKeys;
import dev.keelfy.provenance.paper.SpawnSource;
import dev.keelfy.provenance.paper.world.SpawnContexts;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.bukkit.block.Crafter;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;

/**
 * Item movement and conversion without a player in the loop: hoppers, droppers, hopper minecarts, furnaces, brewing
 * stands, campfires and crafters.
 *
 * @author keelfy
 */
@Singleton
@RequiredArgsConstructor(onConstructor = @__(@Inject))
public final class AutomationListener implements Listener {

    private final Settings settings;
    private final Ledger ledger;
    private final SpawnContexts contexts;

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        val from = Holders.of(event.getSource());
        val to = Holders.of(event.getDestination());
        if (from != null && to != null) {
            ledger.transfer(Cause.HOPPER, from, to, ItemKeys.count(event.getItem()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        val to = Holders.of(event.getInventory());
        val item = event.getItem();
        if (to != null) {
            ledger.transfer(Cause.HOPPER, Holders.of(item), to, ItemKeys.count(item.getItemStack()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSmelt(FurnaceSmeltEvent event) {
        val input = ItemKeys.of(event.getSource());
        val output = ItemKeys.of(event.getResult());
        if (input != null && output != null) {
            ledger.convert(Cause.SMELT, Holders.of(event.getBlock()), Map.of(input, 1L), Map.of(output, (long)
                    event.getResult().getAmount()));
        }
    }

    /**
     * Each brewed bottle becomes its result and takes on the ingredient's origin; untouched bottles stay as they are.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        val contents = event.getContents();
        val results = event.getResults();
        val inputs = new HashMap<ItemKey, Long>();
        val outputs = new HashMap<ItemKey, Long>();

        for (int slot = 0; slot < results.size(); slot++) {
            val bottle = contents.getItem(slot);
            if (bottle != null && !bottle.isSimilar(results.get(slot))) {
                ItemKeys.add(inputs, bottle);
                ItemKeys.add(outputs, results.get(slot));
            }
        }

        val ingredient = ItemKeys.of(contents.getIngredient());
        if (ingredient != null && !outputs.isEmpty()) {
            inputs.merge(ingredient, 1L, Long::sum);
        }
        ledger.convert(Cause.CRAFT, Holders.of(event.getBlock()), inputs, outputs);
    }

    /** A campfire drops what it has cooked; the dropped item carries the pool of the campfire. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCook(BlockCookEvent event) {
        val input = ItemKeys.of(event.getSource());
        val output = ItemKeys.of(event.getResult());
        if (input == null || output == null) {
            return;
        }

        val block = event.getBlock();
        val holder = Holders.of(block);
        ledger.convert(Cause.SMELT, holder, Map.of(input, 1L), Map.of(output, (long)
                event.getResult().getAmount()));
        contexts.add(
                block.getLocation().toCenterLocation(),
                settings.ejectRadius(),
                new SpawnSource(holder, OriginId.UNKNOWN, Cause.SMELT),
                output);
    }

    /** One of each filled slot goes into the result; the crafter then ejects it as a dispense. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CrafterCraftEvent event) {
        val block = event.getBlock();
        if (!(block.getState(false) instanceof Crafter crafter)) {
            return;
        }

        val inputs = new HashMap<ItemKey, Long>();
        for (val stack : crafter.getInventory().getContents()) {
            val key = ItemKeys.of(stack);
            if (key != null) {
                inputs.merge(key, 1L, Long::sum);
            }
        }
        ledger.convert(Cause.CRAFT, Holders.of(block), inputs, ItemKeys.count(event.getResult()));
    }
}

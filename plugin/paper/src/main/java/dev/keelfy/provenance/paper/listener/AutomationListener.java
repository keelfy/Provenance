package dev.keelfy.provenance.paper.listener;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import dev.keelfy.provenance.app.observe.AutomationEvents;
import dev.keelfy.provenance.core.model.ItemKey;
import dev.keelfy.provenance.paper.Holders;
import dev.keelfy.provenance.paper.ItemKeys;
import dev.keelfy.provenance.paper.Positions;
import java.util.ArrayList;
import java.util.HashMap;
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
 * Translates item movement and conversion without a player in the loop (hoppers, droppers, hopper minecarts, furnaces,
 * brewing stands, campfires, crafters) into {@link AutomationEvents}.
 *
 * @author keelfy
 */
@Singleton
@RequiredArgsConstructor(onConstructor = @__(@Inject))
public final class AutomationListener implements Listener {

    private final AutomationEvents automation;

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        val from = Holders.of(event.getSource());
        val to = Holders.of(event.getDestination());
        if (from != null && to != null) {
            automation.moved(from, to, ItemKeys.count(event.getItem()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        val to = Holders.of(event.getInventory());
        val item = event.getItem();
        if (to != null) {
            automation.moved(Holders.of(item), to, ItemKeys.count(item.getItemStack()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSmelt(FurnaceSmeltEvent event) {
        val input = ItemKeys.of(event.getSource());
        val output = ItemKeys.of(event.getResult());
        if (input != null && output != null) {
            automation.smelted(
                    Holders.of(event.getBlock()),
                    input,
                    output,
                    event.getResult().getAmount());
        }
    }

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

        automation.brewed(Holders.of(event.getBlock()), inputs, outputs, ItemKeys.of(contents.getIngredient()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCook(BlockCookEvent event) {
        val input = ItemKeys.of(event.getSource());
        val output = ItemKeys.of(event.getResult());
        if (input == null || output == null) {
            return;
        }

        val block = event.getBlock();
        automation.cooked(
                Holders.of(block),
                Positions.center(block),
                input,
                output,
                event.getResult().getAmount());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CrafterCraftEvent event) {
        val block = event.getBlock();
        if (!(block.getState(false) instanceof Crafter crafter)) {
            return;
        }

        val filledSlots = new ArrayList<ItemKey>();
        for (val stack : crafter.getInventory().getContents()) {
            val key = ItemKeys.of(stack);
            if (key != null) {
                filledSlots.add(key);
            }
        }
        automation.crafted(Holders.of(block), filledSlots, ItemKeys.count(event.getResult()));
    }
}

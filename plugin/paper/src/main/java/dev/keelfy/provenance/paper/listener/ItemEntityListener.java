package dev.keelfy.provenance.paper.listener;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import dev.keelfy.provenance.app.observe.ItemEvents;
import dev.keelfy.provenance.core.model.Cause;
import dev.keelfy.provenance.paper.Holders;
import dev.keelfy.provenance.paper.ItemKeys;
import dev.keelfy.provenance.paper.PaperPlayer;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;

/**
 * Translates what happens to items on the ground (drop, pickup, merge, removal) into {@link ItemEvents}.
 *
 * @author keelfy
 */
@Singleton
@RequiredArgsConstructor(onConstructor = @__(@Inject))
public final class ItemEntityListener implements Listener {

    /** Removal causes after which the entity still exists somewhere and keeps its pool. */
    private static final Set<EntityRemoveEvent.Cause> PERSISTENT =
            Set.of(EntityRemoveEvent.Cause.UNLOAD, EntityRemoveEvent.Cause.PLAYER_QUIT);

    private final ItemEvents items;

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        val drop = event.getItemDrop();
        items.dropped(PaperPlayer.of(event.getPlayer()), Holders.of(drop), ItemKeys.count(drop.getItemStack()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        val item = event.getItem();
        // Paper trims the entity's stack to what the picker can hold before firing the event and reports the rest
        // as getRemaining(), so the stack already is the picked amount (Paper 26.3, ItemEntity#playerTouch).
        val picked = item.getItemStack().getAmount();
        if (ItemKeys.of(item.getItemStack()) == null || picked <= 0) {
            return;
        }

        val counts = ItemKeys.count(item.getItemStack());
        val picker = event.getEntity();
        if (picker instanceof Player player) {
            items.pickedUp(Holders.of(item), PaperPlayer.of(player), counts);
        } else {
            items.pickedUp(Holders.of(item), Holders.of(picker), counts);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMerge(ItemMergeEvent event) {
        val source = event.getEntity();
        val target = event.getTarget();
        val room =
                target.getItemStack().getMaxStackSize() - target.getItemStack().getAmount();
        val moved = Math.min(source.getItemStack().getAmount(), room);
        if (ItemKeys.of(source.getItemStack()) == null || moved <= 0) {
            return;
        }

        items.merged(
                Holders.of(source),
                Holders.of(target),
                ItemKeys.count(source.getItemStack().asQuantity(moved)));
    }

    private static Cause why(EntityRemoveEvent.Cause cause) {
        return switch (cause) {
            case DESPAWN -> Cause.DESPAWN;
            case DEATH -> Cause.DESTROY; // fire, lava, explosions, cactus, the void
            default -> Cause.DROP;
        };
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveEvent event) {
        val entity = event.getEntity();
        if (entity instanceof Player || PERSISTENT.contains(event.getCause())) {
            return;
        }
        items.removed(
                Holders.of(entity),
                entity instanceof Item ? why(event.getCause()) : Cause.DROP,
                entity instanceof Hanging);
    }
}

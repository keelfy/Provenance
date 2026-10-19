package dev.keelfy.provenance.paper.listener;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import dev.keelfy.provenance.app.observe.ItemAmount;
import dev.keelfy.provenance.app.observe.WorldEvents;
import dev.keelfy.provenance.core.model.OriginId;
import dev.keelfy.provenance.paper.Holders;
import dev.keelfy.provenance.paper.ItemKeys;
import dev.keelfy.provenance.paper.PaperPlayer;
import dev.keelfy.provenance.paper.Positions;
import io.papermc.paper.block.TileStateInventoryHolder;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.jspecify.annotations.Nullable;

/**
 * Translates world events (mining, kills, loot, fishing, dispensers, container blocks) into {@link WorldEvents}.
 *
 * @author keelfy
 */
@Singleton
@RequiredArgsConstructor(onConstructor = @__(@Inject))
public final class WorldListener implements Listener {

    private final WorldEvents world;

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        containerDestroyed(event.getBlock(), PaperPlayer.of(event.getPlayer()).origin());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().forEach(block -> containerDestroyed(block, OriginId.WORLD));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().forEach(block -> containerDestroyed(block, OriginId.WORLD));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDrop(BlockDropItemEvent event) {
        world.blockDrops(
                PaperPlayer.of(event.getPlayer()).origin(),
                event.getItems().stream().map(Positions::drop).toList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        val key = ItemKeys.of(event.getItem());
        if (key != null) {
            world.dispensed(Holders.of(event.getBlock()), Positions.center(event.getBlock()), key);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        val block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        val state = block.getState(false);
        val holder = state instanceof Container ? null : Holders.tile(state);
        if (holder == null) {
            return;
        }

        world.tileClicked(
                PaperPlayer.of(event.getPlayer()),
                holder,
                Positions.center(block),
                ItemKeys.count(state).keySet(),
                () -> ItemKeys.count(block.getState(false)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        val block = event.getBlock();
        val item = ItemKeys.of(event.getItemInHand());
        if (item != null) {
            world.blockPlaced(
                    PaperPlayer.of(event.getPlayer()), Holders.of(block), item, ItemKeys.inside(event.getItemInHand()));
        }

        if (block.getState(false) instanceof Container) {
            world.containerPlaced(() -> joined(block));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        val entity = event.getEntity();
        if (entity instanceof Player player) {
            world.playerDied(PaperPlayer.of(player), Positions.of(player.getLocation()));
            return;
        }

        val killer = entity.getKiller();
        world.mobKilled(
                Holders.of(entity),
                Positions.of(entity.getLocation()),
                killer != null ? PaperPlayer.of(killer).origin() : null,
                event.getDrops().stream().map(drop -> ItemKeys.count(drop)).toList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        world.itemSpawned(Positions.drop(event.getEntity()), event.getEntity().getThrower() != null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLoot(LootGenerateEvent event) {
        val loot = new ArrayList<ItemAmount>();
        for (val stack : event.getLoot()) {
            val key = ItemKeys.of(stack);
            if (key != null) {
                loot.add(new ItemAmount(key, stack.getAmount()));
            }
        }
        world.looted(Holders.of(event.getInventoryHolder()), loot);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH && event.getCaught() instanceof Item item) {
            world.fished(PaperPlayer.of(event.getPlayer()).origin(), Positions.drop(item));
        }
    }

    private void containerDestroyed(Block block, OriginId fallback) {
        val state = block.getState(false);
        val holder = Holders.tile(state);
        if (holder == null) {
            return;
        }

        val halves = state instanceof TileStateInventoryHolder tile ? halves(tile) : List.of(block.getLocation());
        world.containerDestroyed(
                holder, ItemKeys.count(state), Positions.center(block), fallback, () -> survivor(halves));
    }

    private static WorldEvents.@Nullable Remains survivor(List<Location> halves) {
        for (val half : halves) {
            if (half.getBlock().getState(false) instanceof Container survivor) {
                val id = Holders.of(survivor.getInventory());
                if (id != null) {
                    return new WorldEvents.Remains(id, ItemKeys.count(survivor.getInventory()));
                }
            }
        }
        return null;
    }

    private static WorldEvents.@Nullable Halves joined(Block block) {
        if (!(block.getState(false) instanceof Container container)) {
            return null;
        }
        val canonical = Holders.of(container.getInventory());
        return canonical != null
                ? new WorldEvents.Halves(
                        canonical, halves(container).stream().map(Holders::of).toList())
                : null;
    }

    private static List<Location> halves(TileStateInventoryHolder container) {
        val result = new ArrayList<Location>();
        if (container.getInventory().getHolder(false) instanceof DoubleChest chest) {
            addLocation(result, chest.getLeftSide(false));
            addLocation(result, chest.getRightSide(false));
        } else {
            result.add(container.getLocation());
        }
        return result;
    }

    private static void addLocation(List<Location> result, @Nullable Object side) {
        if (side instanceof Container half) {
            result.add(half.getLocation());
        }
    }
}

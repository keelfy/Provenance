package dev.keelfy.provenance.paper.listener;

import java.util.ArrayList;
import java.util.List;

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
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.inject.Singleton;
import com.google.inject.Inject;

import dev.keelfy.provenance.core.HolderId;
import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.core.OriginId;
import dev.keelfy.provenance.paper.Holders;
import dev.keelfy.provenance.paper.ItemKeys;
import dev.keelfy.provenance.paper.Players;
import dev.keelfy.provenance.paper.SpawnSource;
import dev.keelfy.provenance.paper.ViewTarget;
import dev.keelfy.provenance.paper.inventory.InventoryReconciler;
import dev.keelfy.provenance.paper.world.SpawnContexts;
import io.papermc.paper.block.TileStateInventoryHolder;
import lombok.RequiredArgsConstructor;
import lombok.val;

/**
 * Where items enter the world (mining, kills, loot, fishing, dispensers) and how container blocks keep their pools
 * when they are broken, blown up or joined into a double chest.
 *
 * @author keelfy
 */
@Singleton 
@RequiredArgsConstructor(onConstructor = @__(@Inject))
public final class WorldListener implements Listener {

    private static final double BLOCK_RADIUS = 2.0;
    private static final double DISPENSE_RADIUS = 1.0;
    private static final double PLAYER_DEATH_RADIUS = 3.0;

    private final @NotNull Plugin plugin;
    private final @NotNull Ledger ledger;
    private final @NotNull SpawnContexts contexts;
    private final @NotNull InventoryReconciler reconciler;

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        containerDestroyed(event.getBlock(), Players.origin(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().forEach(block -> containerDestroyed(block, OriginId.UNKNOWN));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().forEach(block -> containerDestroyed(block, OriginId.UNKNOWN));
    }

    /** Mined drops belong to the miner; container contents spilled by the same break keep their history. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDrop(BlockDropItemEvent event) {
        val fallback = new SpawnSource(null, Players.origin(event.getPlayer()));
        for (val item : event.getItems()) {
            val source = contexts.find(item.getLocation(), ItemKeys.of(item.getItemStack()));
            spawn(item, source != null ? source : fallback);
        }
    }

    /** A dispenser or dropper throwing an item out: the item entity it spawns carries the pool of the block. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        val key = ItemKeys.of(event.getItem());
        if (key == null) {
            return;
        }
        
        val block = event.getBlock();
        val spawnSource = new SpawnSource(Holders.of(block), OriginId.UNKNOWN);
        contexts.add(block.getLocation().toCenterLocation(), DISPENSE_RADIUS, spawnSource, key);
    }

    /**
     * Jukeboxes, chiseled bookshelves, decorated pots, lecterns and campfires swap items by clicking the block, with no view to observe:
     * the click is settled like a container, and whatever the block pops out carries its pool.
     */
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

        val source = new SpawnSource(holder, OriginId.UNKNOWN);
        for (val item : ItemKeys.count(state).keySet()) {
            contexts.add(block.getLocation().toCenterLocation(), BLOCK_RADIUS, source, item);
        }
        reconciler.track(event.getPlayer(), ViewTarget.container(holder, () -> ItemKeys.count(block.getState(false))));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        val block = event.getBlock();
        // A placed shulker box keeps what it carried: that moves from the player to the block.
        ledger.transfer(Holders.of(event.getPlayer()), Holders.of(block), ItemKeys.inside(event.getItemInHand()));

        if (!(block.getState(false) instanceof Container)) {
            return;
        }
        // A chest placed next to another becomes one holder keyed by the left half.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!(block.getState(false) instanceof Container container)) {
                return;
            }
            val canonical = Holders.of(container.getInventory());
            for (val half : halves(container)) {
                val id = Holders.of(half);
                if (canonical != null && !id.equals(canonical)) {
                    ledger.transferAll(id, canonical);
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        val entity = event.getEntity();
        if (entity instanceof Player player) {
            reconciler.reconcile(player);
            contexts.add(player.getLocation(), PLAYER_DEATH_RADIUS, new SpawnSource(Holders.of(player), OriginId.UNKNOWN));
            return;
        }

        val killer = entity.getKiller();
        val origin = killer != null ? Players.origin(killer) : OriginId.MOB;
        val holder = Holders.of(entity);
        val from = ledger.getPools(holder).isEmpty() ? null : holder;
        contexts.add(entity.getLocation(), BLOCK_RADIUS, new SpawnSource(from, origin));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        val source = contexts.find(event.getLocation(), ItemKeys.of(event.getEntity().getItemStack()));
        if (source != null) {
            spawn(event.getEntity(), source);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLoot(LootGenerateEvent event) {
        val holder = Holders.of(event.getInventoryHolder());
        if (holder == null || holder.isPlayer()) {
            return;
        }
        for (val stack : event.getLoot()) {
            val key = ItemKeys.of(stack);
            if (key != null) {
                ledger.mint(holder, key, stack.getAmount(), OriginId.LOOT);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH && event.getCaught() instanceof Item item) {
            spawn(item, new SpawnSource(null, Players.origin(event.getPlayer())));
        }
    }

    /** Attributes an item entity once; later hints for the same entity are ignored. */
    private void spawn(@NotNull Item item, @NotNull SpawnSource source) {
        val holder = Holders.of(item);
        if (!ledger.getPools(holder).isEmpty()) {
            return;
        }
        ItemKeys.count(item.getItemStack()).forEach((key, amount) -> source.apply(ledger, holder, key, amount));
    }

    private void containerDestroyed(@NotNull Block block, @NotNull OriginId fallback) {
        val state = block.getState(false);
        val holder = Holders.tile(state);
        if (holder == null) {
            return;
        }
        // Untracked contents spill as UNKNOWN, not as the breaker's production; the fallback covers the block itself.
        ledger.reconcile(holder, ItemKeys.count(state));
        val halves = state instanceof TileStateInventoryHolder tile ? halves(tile) : List.of(block.getLocation());
        contexts.add(block.getLocation().toCenterLocation(), BLOCK_RADIUS, new SpawnSource(holder, fallback));

        // After the spill: what is left belongs to the surviving half of a double chest, if any.
        plugin.getServer().getScheduler().runTask(plugin, () -> settleRemains(holder, halves));
    }

    private void settleRemains(@NotNull HolderId holder, @NotNull List<Location> halves) {
        for (val half : halves) {
            if (half.getBlock().getState(false) instanceof Container survivor) {
                val id = Holders.of(survivor.getInventory());
                if (id != null) {
                    if (!id.equals(holder)) {
                        ledger.transferAll(holder, id);
                    }
                    ledger.reconcile(id, ItemKeys.count(survivor.getInventory()));
                    return;
                }
            }
        }
        ledger.clear(holder);
    }

    private static @NotNull List<Location> halves(@NotNull TileStateInventoryHolder container) {
        val result = new ArrayList<Location>();
        if (container.getInventory().getHolder(false) instanceof DoubleChest chest) {
            addLocation(result, chest.getLeftSide(false));
            addLocation(result, chest.getRightSide(false));
        } else {
            result.add(container.getLocation());
        }
        return result;
    }

    private static void addLocation(@NotNull List<Location> result, @Nullable Object side) {
        if (side instanceof Container half) {
            result.add(half.getLocation());
        }
    }
}

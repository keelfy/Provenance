package dev.keelfy.provenance.paper.listener;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import dev.keelfy.provenance.Settings;
import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.core.model.Cause;
import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.OriginId;
import dev.keelfy.provenance.paper.Holders;
import dev.keelfy.provenance.paper.ItemKeys;
import dev.keelfy.provenance.paper.Players;
import dev.keelfy.provenance.paper.SpawnSource;
import dev.keelfy.provenance.paper.ViewTarget;
import dev.keelfy.provenance.paper.inventory.InventoryReconciler;
import dev.keelfy.provenance.paper.world.SpawnContexts;
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
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Where items enter the world (mining, kills, loot, fishing, dispensers) and how container blocks keep their pools when
 * they are broken, blown up or joined into a double chest.
 *
 * @author keelfy
 */
@Singleton
@RequiredArgsConstructor(onConstructor = @__(@Inject))
public final class WorldListener implements Listener {

    private final Settings settings;
    private final Plugin plugin;
    private final Ledger ledger;
    private final SpawnContexts contexts;
    private final InventoryReconciler reconciler;

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        containerDestroyed(event.getBlock(), Players.origin(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().forEach(block -> containerDestroyed(block, OriginId.WORLD));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().forEach(block -> containerDestroyed(block, OriginId.WORLD));
    }

    /** Mined drops belong to the miner; container contents spilled by the same break keep their history. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDrop(BlockDropItemEvent event) {
        val fallback = new SpawnSource(null, Players.origin(event.getPlayer()), Cause.MINE);
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
        val spawnSource = new SpawnSource(Holders.of(block), OriginId.UNKNOWN, Cause.DROP);
        contexts.add(block.getLocation().toCenterLocation(), settings.dispenseRadius(), spawnSource, key);
    }

    /**
     * Jukeboxes, chiseled bookshelves, decorated pots, lecterns and campfires swap items by clicking the block, with no
     * view to observe: the click is settled like a container, and whatever the block pops out carries its pool.
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

        val source = new SpawnSource(holder, OriginId.UNKNOWN, Cause.CONTAINER);
        for (val item : ItemKeys.count(state).keySet()) {
            contexts.add(block.getLocation().toCenterLocation(), settings.blockRadius(), source, item);
        }
        reconciler.track(event.getPlayer(), ViewTarget.container(holder, () -> ItemKeys.count(block.getState(false))));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        val block = event.getBlock();
        // A placed shulker box keeps what it carried: that moves from the player to the block.
        ledger.transfer(
                Cause.CONTAINER,
                Holders.of(event.getPlayer()),
                Holders.of(block),
                ItemKeys.inside(event.getItemInHand()));

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
                    ledger.transferAll(Cause.CONTAINER, id, canonical);
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        val entity = event.getEntity();
        if (entity instanceof Player player) {
            reconciler.reconcile(player);
            contexts.add(
                    player.getLocation(),
                    settings.deathRadius(),
                    new SpawnSource(Holders.of(player), OriginId.UNKNOWN, Cause.KILL));
            return;
        }

        val killer = entity.getKiller();
        val origin = killer != null ? Players.origin(killer) : OriginId.MOB;
        val holder = Holders.of(entity);
        // The creature drops its loot: that way the history says what was killed. What it already carried stays its
        // own.
        for (val drop : event.getDrops()) {
            ItemKeys.count(drop).forEach((key, amount) -> {
                val missing = amount - ledger.getCount(holder, key);
                if (missing > 0) {
                    ledger.mint(Cause.KILL, holder, key, missing, origin);
                }
            });
        }
        contexts.add(entity.getLocation(), settings.blockRadius(), new SpawnSource(holder, origin, Cause.KILL));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        val item = event.getEntity();
        val source = contexts.find(event.getLocation(), ItemKeys.of(item.getItemStack()));
        if (source != null) {
            spawn(item, source);
        } else if (item.getThrower() == null) {
            // Nobody threw it and nothing modelled dropped it: the world did (water, decay, explosions, fire).
            spawn(item, new SpawnSource(null, OriginId.WORLD, Cause.DROP));
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
                ledger.mint(Cause.LOOT, holder, key, stack.getAmount(), OriginId.LOOT);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH && event.getCaught() instanceof Item item) {
            spawn(item, new SpawnSource(null, Players.origin(event.getPlayer()), Cause.LOOT));
        }
    }

    /** Attributes an item entity once; later hints for the same entity are ignored. */
    private void spawn(Item item, SpawnSource source) {
        val holder = Holders.of(item);
        if (!ledger.getPools(holder).isEmpty()) {
            return;
        }
        ItemKeys.count(item.getItemStack()).forEach((key, amount) -> source.apply(ledger, holder, key, amount));
    }

    private void containerDestroyed(Block block, OriginId fallback) {
        val state = block.getState(false);
        val holder = Holders.tile(state);
        if (holder == null) {
            return;
        }
        // Untracked contents spill as UNKNOWN, not as the breaker's production; the fallback covers the block itself.
        ledger.reconcile(Cause.RECONCILE, holder, ItemKeys.count(state));
        val halves = state instanceof TileStateInventoryHolder tile ? halves(tile) : List.of(block.getLocation());
        contexts.add(
                block.getLocation().toCenterLocation(),
                settings.blockRadius(),
                new SpawnSource(holder, fallback, Cause.MINE));

        // After the spill: what is left belongs to the surviving half of a double chest, if any.
        plugin.getServer().getScheduler().runTask(plugin, () -> settleRemains(holder, halves));
    }

    private void settleRemains(Holder holder, List<Location> halves) {
        for (val half : halves) {
            if (half.getBlock().getState(false) instanceof Container survivor) {
                val id = Holders.of(survivor.getInventory());
                if (id != null) {
                    if (!id.equals(holder)) {
                        ledger.transferAll(Cause.CONTAINER, holder, id);
                    }
                    ledger.reconcile(Cause.RECONCILE, id, ItemKeys.count(survivor.getInventory()));
                    return;
                }
            }
        }
        ledger.clear(Cause.CONTAINER, holder);
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

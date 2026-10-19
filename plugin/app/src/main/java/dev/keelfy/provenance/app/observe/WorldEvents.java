package dev.keelfy.provenance.app.observe;

import dev.keelfy.provenance.app.Settings;
import dev.keelfy.provenance.app.inventory.InventoryReconciler;
import dev.keelfy.provenance.app.inventory.ViewTarget;
import dev.keelfy.provenance.app.port.OnlinePlayer;
import dev.keelfy.provenance.app.port.Scheduler;
import dev.keelfy.provenance.app.world.ItemDrop;
import dev.keelfy.provenance.app.world.SpawnContexts;
import dev.keelfy.provenance.app.world.SpawnSource;
import dev.keelfy.provenance.app.world.WorldPos;
import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.core.model.Cause;
import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.ItemKey;
import dev.keelfy.provenance.core.model.OriginId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.jspecify.annotations.Nullable;

/**
 * Where items enter the world (mining, kills, loot, fishing, dispensers) and how container blocks keep their pools when
 * they are broken, blown up or joined into a double chest. The platform reports what happened; this decides what it
 * means for the ledger.
 *
 * @author keelfy
 */
@RequiredArgsConstructor
public final class WorldEvents {

    private final Settings settings;
    private final Ledger ledger;
    private final SpawnContexts contexts;
    private final InventoryReconciler reconciler;
    private final Scheduler scheduler;

    /** What stands where a container was broken: the surviving half of a double chest. */
    public record Remains(Holder holder, Map<ItemKey, Long> contents) {}

    /** The halves of a freshly placed double chest and the holder they now share. */
    public record Halves(Holder canonical, List<Holder> halves) {}

    /**
     * A container block is gone. Its untracked contents spill as {@code UNKNOWN}, not as the breaker's production; the
     * {@code fallback} covers the block itself.
     *
     * @param contents what the container held
     * @param survivor looked up a tick later, after the spill: what is left belongs to the surviving half of a double
     *     chest, if any
     */
    public void containerDestroyed(
            Holder holder,
            Map<ItemKey, Long> contents,
            WorldPos center,
            OriginId fallback,
            Supplier<@Nullable Remains> survivor) {
        ledger.reconcile(Cause.RECONCILE, holder, contents);
        contexts.add(center, settings.blockRadius(), new SpawnSource(holder, fallback, Cause.MINE));
        scheduler.runNextTick(() -> settleRemains(holder, survivor.get()));
    }

    /** Mined drops belong to the miner; container contents spilled by the same break keep their history. */
    public void blockDrops(OriginId miner, List<ItemDrop> drops) {
        val fallback = new SpawnSource(null, miner, Cause.MINE);
        for (val drop : drops) {
            val source = contexts.find(drop.at(), drop.key());
            spawn(drop, source != null ? source : fallback);
        }
    }

    /** A dispenser or dropper throwing an item out: the item entity it spawns carries the pool of the block. */
    public void dispensed(Holder block, WorldPos center, ItemKey item) {
        contexts.add(center, settings.dispenseRadius(), new SpawnSource(block, OriginId.UNKNOWN, Cause.DROP), item);
    }

    /**
     * Jukeboxes, chiseled bookshelves, decorated pots, lecterns and campfires swap items by clicking the block, with no
     * view to observe: the click is settled like a container, and whatever the block pops out carries its pool.
     *
     * @param held what the block held before the click
     * @param contents what it holds right now
     */
    public void tileClicked(
            OnlinePlayer player,
            Holder tile,
            WorldPos center,
            Set<ItemKey> held,
            Supplier<Map<ItemKey, Long>> contents) {
        val source = new SpawnSource(tile, OriginId.UNKNOWN, Cause.CONTAINER);
        held.forEach(item -> contexts.add(center, settings.blockRadius(), source, item));
        reconciler.track(player, ViewTarget.container(tile, contents));
    }

    /**
     * The placed block item is used up. A placed shulker box keeps what it carried: that moves from the player to the
     * block.
     */
    public void blockPlaced(OnlinePlayer placer, Holder block, ItemKey item, Map<ItemKey, Long> carried) {
        reconciler.spend(placer, Cause.PLACE, item);
        ledger.transfer(Cause.CONTAINER, placer.holder(), block, carried);
    }

    /**
     * A chest placed next to another becomes one holder keyed by the left half.
     *
     * @param joined looked up a tick later, once the server has joined the halves; {@code null} if it did not
     */
    public void containerPlaced(Supplier<@Nullable Halves> joined) {
        scheduler.runNextTick(() -> {
            val halves = joined.get();
            if (halves == null) {
                return;
            }
            for (val half : halves.halves()) {
                if (!half.equals(halves.canonical())) {
                    ledger.transferAll(Cause.CONTAINER, half, halves.canonical());
                }
            }
        });
    }

    public void playerDied(OnlinePlayer player, WorldPos at) {
        reconciler.reconcile(player);
        contexts.add(at, settings.deathRadius(), new SpawnSource(player.holder(), OriginId.UNKNOWN, Cause.KILL));
    }

    /**
     * The creature drops its loot: that way the history says what was killed. What it already carried stays its own.
     *
     * @param killer who killed it, {@code null} for the world
     * @param drops the counts of every stack it drops
     */
    public void mobKilled(Holder mob, WorldPos at, @Nullable OriginId killer, List<Map<ItemKey, Long>> drops) {
        val origin = killer != null ? killer : OriginId.MOB;
        for (val drop : drops) {
            drop.forEach((key, amount) -> {
                val missing = amount - ledger.getCount(mob, key);
                if (missing > 0) {
                    ledger.mint(Cause.KILL, mob, key, missing, origin);
                }
            });
        }
        contexts.add(at, settings.blockRadius(), new SpawnSource(mob, origin, Cause.KILL));
    }

    /** An item entity appeared. {@code thrown} tells that somebody threw it, so it is not the world's. */
    public void itemSpawned(ItemDrop drop, boolean thrown) {
        val source = contexts.find(drop.at(), drop.key());
        if (source != null) {
            spawn(drop, source);
        } else if (!thrown) {
            // Nobody threw it and nothing modelled dropped it: the world did (water, decay, explosions, fire).
            spawn(drop, new SpawnSource(null, OriginId.WORLD, Cause.DROP));
        }
    }

    /** Loot generated into a container; players' inventories and unresolved holders are not loot containers. */
    public void looted(@Nullable Holder container, List<ItemAmount> loot) {
        if (container == null || container.isPlayer()) {
            return;
        }
        loot.forEach(stack -> ledger.mint(Cause.LOOT, container, stack.item(), stack.amount(), OriginId.LOOT));
    }

    public void fished(OriginId angler, ItemDrop caught) {
        spawn(caught, new SpawnSource(null, angler, Cause.LOOT));
    }

    /** Attributes an item entity once; later hints for the same entity are ignored. */
    private void spawn(ItemDrop drop, SpawnSource source) {
        if (!ledger.getPools(drop.entity()).isEmpty()) {
            return;
        }
        drop.counts().forEach((key, amount) -> source.apply(ledger, drop.entity(), key, amount));
    }

    private void settleRemains(Holder holder, @Nullable Remains survivor) {
        if (survivor == null) {
            ledger.clear(Cause.CONTAINER, holder);
            return;
        }
        if (!survivor.holder().equals(holder)) {
            ledger.transferAll(Cause.CONTAINER, holder, survivor.holder());
        }
        ledger.reconcile(Cause.RECONCILE, survivor.holder(), survivor.contents());
    }
}

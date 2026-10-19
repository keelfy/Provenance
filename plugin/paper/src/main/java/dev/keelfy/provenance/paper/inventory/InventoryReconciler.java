package dev.keelfy.provenance.paper.inventory;

import dev.keelfy.provenance.Settings;
import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.core.Settlement;
import dev.keelfy.provenance.core.model.Cause;
import dev.keelfy.provenance.core.model.ItemKey;
import dev.keelfy.provenance.core.model.OriginId;
import dev.keelfy.provenance.paper.Holders;
import dev.keelfy.provenance.paper.ItemKeys;
import dev.keelfy.provenance.paper.Players;
import dev.keelfy.provenance.paper.ViewTarget;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.val;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** @author keelfy */
public final class InventoryReconciler {

    private final Plugin plugin;
    private final Ledger ledger;
    private final Settings settings;
    private final Set<UUID> pending = new HashSet<>();

    public InventoryReconciler(Plugin plugin, Ledger ledger, Settings settings) {
        this.plugin = plugin;
        this.ledger = ledger;
        this.settings = settings;
    }

    public void runScheduler() {
        plugin.getServer()
                .getScheduler()
                .runTaskTimer(plugin, this::scheduled, settings.reconcileTicks(), settings.reconcileTicks());
    }

    private void scheduled() {
        plugin.getServer().getOnlinePlayers().forEach(this::reconcile);
    }

    /** Aligns the ledger with the player's actual items unless an interaction is being settled this tick. */
    public void reconcile(Player player) {
        reconcile(player, Map.of());
    }

    public void reconcile(Player player, Map<ItemKey, Long> inFlight) {
        if (!pending.contains(player.getUniqueId())) {
            val actual = Players.counts(player);
            inFlight.forEach((item, amount) -> actual.merge(item, amount, (a, b) -> a + b));
            ledger.reconcile(Cause.RECONCILE, Holders.of(player), actual, Players.mintOrigin(player));
        }
    }

    /**
     * The server shrinks the cursor before firing a drag event and fills the slots after it, so the dragged items are
     * momentarily in neither place.
     */
    public Map<ItemKey, Long> inFlight(ItemStack old, ItemStack left) {
        val key = ItemKeys.of(old);
        val moved = old.getAmount() - (key != null && key.equals(ItemKeys.of(left)) ? left.getAmount() : 0);
        return key != null && moved > 0 ? Map.of(key, (long) moved) : Map.of();
    }

    /** Settles an interaction with a block that holds items without opening a view, e.g. a jukebox. */
    public void track(Player player, ViewTarget target) {
        track(player, target, Map.of());
    }

    public void track(HumanEntity who, ViewTarget target, Map<ItemKey, Long> inFlight) {
        if (!(who instanceof Player player)) {
            return;
        }

        track(player, target, inFlight);
    }

    public void track(Player player, ViewTarget target, Map<ItemKey, Long> inFlight) {
        if (isSettling(player)) {
            return;
        }

        reconcile(player, inFlight);
        val container = target.container();
        if (container != null) {
            ledger.reconcile(Cause.RECONCILE, container, target.contents().get());
        }

        pending.add(player.getUniqueId());
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            pending.remove(player.getUniqueId());
            settle(player, target);
        });
    }

    public boolean isSettling(Player player) {
        return pending.contains(player.getUniqueId());
    }

    private void settle(Player player, ViewTarget target) {
        val holder = Holders.of(player);
        val actual = Players.counts(player);

        switch (target.kind()) {
            case CONTAINER ->
                new Settlement(ledger, Cause.CONTAINER, Players.mintOrigin(player))
                        .container(
                                holder,
                                actual,
                                target.container(),
                                target.contents().get());
            case WORKSTATION ->
                new Settlement(ledger, Cause.CRAFT, Players.mintOrigin(player)).workstation(holder, actual);
            case MERCHANT -> ledger.reconcile(Cause.TRADE, holder, actual, OriginId.TRADE);
            case SELF -> ledger.reconcile(Cause.RECONCILE, holder, actual, Players.mintOrigin(player));
        }
    }
}

package dev.keelfy.provenance.paper.inventory;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import dev.keelfy.provenance.core.ItemKey;
import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.core.OriginId;
import dev.keelfy.provenance.core.Settlement;
import dev.keelfy.provenance.paper.Holders;
import dev.keelfy.provenance.paper.ItemKeys;
import dev.keelfy.provenance.paper.Players;
import dev.keelfy.provenance.paper.ViewTarget;
import lombok.val;

/**
 * @author keelfy
 */
public final class InventoryReconciler {
    
    /** Safety net for item changes no listener models (placing, eating, tool breaks, other plugins). */
    private static final long RECONCILE_PERIOD_TICKS = 100;

    private final @NotNull Plugin plugin;
    private final @NotNull Ledger ledger;
    private final @NotNull Set<UUID> pending = new HashSet<>();

    public InventoryReconciler(@NotNull Plugin plugin, @NotNull Ledger ledger) {
        this.plugin = plugin;
        this.ledger = ledger;
    }

    public void runScheduler() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::scheduled, RECONCILE_PERIOD_TICKS, RECONCILE_PERIOD_TICKS);
    }

    private void scheduled() {
        plugin.getServer().getOnlinePlayers().forEach(this::reconcile);
    }

    /** Aligns the ledger with the player's actual items unless an interaction is being settled this tick. */
    public void reconcile(@NotNull Player player) {
        reconcile(player, Map.of());
    }

    public void reconcile(@NotNull Player player, @NotNull Map<ItemKey, Long> inFlight) {
        if (!pending.contains(player.getUniqueId())) {
            val actual = Players.counts(player);
            inFlight.forEach((item, amount) -> actual.merge(item, amount, (a, b) -> a + b));
            ledger.reconcile(Holders.of(player), actual, Players.mintOrigin(player));
        }
    }

    /**
     * The server shrinks the cursor before firing a drag event and fills the slots after it,
     * so the dragged items are momentarily in neither place.
     */
    public @NotNull Map<ItemKey, Long> inFlight(@NotNull ItemStack old, @NotNull ItemStack left) {
        val key = ItemKeys.of(old);
        val moved = old.getAmount() - (key != null && key.equals(ItemKeys.of(left)) ? left.getAmount() : 0);
        return key != null && moved > 0 ? Map.of(key, (long) moved) : Map.of();
    }

    /** Settles an interaction with a block that holds items without opening a view, e.g. a jukebox. */
    public void track(@NotNull Player player, @NotNull ViewTarget target) {
        track(player, target, Map.of());
    }

    public void track(@NotNull HumanEntity who, @NotNull ViewTarget target, @NotNull Map<ItemKey, Long> inFlight) {
        if (!(who instanceof Player player)) {
            return;
        }

        track(player, target, inFlight);
    }

    public void track(@NotNull Player player, @NotNull ViewTarget target, @NotNull Map<ItemKey, Long> inFlight) {
        if (isSettling(player)) {
            return;
        }

        reconcile(player, inFlight);
        if (target.container() != null) {
            ledger.reconcile(target.container(), target.contents().get());
        }

        pending.add(player.getUniqueId());
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            pending.remove(player.getUniqueId());
            settle(player, target);
        });
    }

    public boolean isSettling(@NotNull Player player) {
        return pending.contains(player.getUniqueId());
    }

    private void settle(@NotNull Player player, @NotNull ViewTarget target) {
        val holder = Holders.of(player);
        val actual = Players.counts(player);

        switch (target.kind()) {
            case CONTAINER -> Settlement.container(ledger, holder, actual,
                    target.container(), target.contents().get(), Players.mintOrigin(player));
            case WORKSTATION -> Settlement.workstation(ledger, holder, actual, Players.mintOrigin(player));
            case MERCHANT -> ledger.reconcile(holder, actual, OriginId.TRADE);
            case SELF -> ledger.reconcile(holder, actual, Players.mintOrigin(player));
        }
    }
}

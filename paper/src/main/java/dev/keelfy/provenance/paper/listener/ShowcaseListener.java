package dev.keelfy.provenance.paper.listener;

import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.jetbrains.annotations.NotNull;

import com.google.inject.Singleton;
import com.google.inject.Inject;

import dev.keelfy.provenance.core.OriginId;
import dev.keelfy.provenance.paper.Holders;
import dev.keelfy.provenance.paper.ItemKeys;
import dev.keelfy.provenance.paper.SpawnSource;
import dev.keelfy.provenance.paper.ViewTarget;
import dev.keelfy.provenance.paper.inventory.InventoryReconciler;
import dev.keelfy.provenance.paper.world.SpawnContexts;
import lombok.RequiredArgsConstructor;
import lombok.val;

/**
 * Item frames and armor stands: they keep items without a view, so an interaction is settled like a container,
 * and an item knocked out of a frame carries the frame's pool. Items of a broken armor stand are covered by the death hint.
 *
 * @author keelfy
 */
@Singleton 
@RequiredArgsConstructor(onConstructor = @__(@Inject))
public final class ShowcaseListener implements Listener {

    private static final double SPILL_RADIUS = 1.5;

    private final @NotNull SpawnContexts contexts;
    private final @NotNull InventoryReconciler reconciler;

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFrameInteract(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof ItemFrame frame) {
            val target = ViewTarget.container(Holders.of(frame), () -> ItemKeys.count(frame));
            reconciler.track(event.getPlayer(), target);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStandManipulate(PlayerArmorStandManipulateEvent event) {
        val stand = event.getRightClicked();
        val target = ViewTarget.container(Holders.of(stand), () -> ItemKeys.count(stand));
        reconciler.track(event.getPlayer(), target);
    }

    /** A hit pops the item out of the frame. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFrameDamage(EntityDamageEvent event) {
        hintSpill(event.getEntity());
    }

    /** Breaking the frame (explosion, lost support) spills the item too. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFrameBreak(HangingBreakEvent event) {
        hintSpill(event.getEntity());
    }

    private void hintSpill(@NotNull Entity entity) {
        if (!(entity instanceof ItemFrame frame)) {
            return;
        }
        val key = ItemKeys.of(frame.getItem());
        if (key != null) {
            val spawnSource = new SpawnSource(Holders.of(frame), OriginId.UNKNOWN);
            contexts.add(frame.getLocation(), SPILL_RADIUS, spawnSource, key);
        }
    }
}

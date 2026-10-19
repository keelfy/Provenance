package dev.keelfy.provenance.paper.listener;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import dev.keelfy.provenance.app.observe.ShowcaseEvents;
import dev.keelfy.provenance.paper.Holders;
import dev.keelfy.provenance.paper.ItemKeys;
import dev.keelfy.provenance.paper.PaperPlayer;
import dev.keelfy.provenance.paper.Positions;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;

/**
 * Translates interactions with item frames and armor stands into {@link ShowcaseEvents}.
 *
 * @author keelfy
 */
@Singleton
@RequiredArgsConstructor(onConstructor = @__(@Inject))
public final class ShowcaseListener implements Listener {

    private final ShowcaseEvents showcases;

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFrameInteract(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof ItemFrame frame) {
            showcases.interacted(PaperPlayer.of(event.getPlayer()), Holders.of(frame), () -> ItemKeys.count(frame));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStandManipulate(PlayerArmorStandManipulateEvent event) {
        val stand = event.getRightClicked();
        showcases.interacted(PaperPlayer.of(event.getPlayer()), Holders.of(stand), () -> ItemKeys.count(stand));
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

    private void hintSpill(Entity entity) {
        if (!(entity instanceof ItemFrame frame)) {
            return;
        }
        val key = ItemKeys.of(frame.getItem());
        if (key != null) {
            showcases.spills(Holders.of(frame), Positions.of(frame.getLocation()), key);
        }
    }
}

package dev.keelfy.provenance.app.observe;

import dev.keelfy.provenance.app.Settings;
import dev.keelfy.provenance.app.inventory.InventoryReconciler;
import dev.keelfy.provenance.app.inventory.ViewTarget;
import dev.keelfy.provenance.app.port.OnlinePlayer;
import dev.keelfy.provenance.app.world.SpawnContexts;
import dev.keelfy.provenance.app.world.SpawnSource;
import dev.keelfy.provenance.app.world.WorldPos;
import dev.keelfy.provenance.core.model.Cause;
import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.ItemKey;
import dev.keelfy.provenance.core.model.OriginId;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;

/**
 * Item frames and armor stands: they keep items without a view, so an interaction is settled like a container, and an
 * item knocked out of a frame carries the frame's pool. Items of a broken armor stand are covered by the death hint.
 *
 * @author keelfy
 */
@RequiredArgsConstructor
public final class ShowcaseEvents {

    private final Settings settings;
    private final SpawnContexts contexts;
    private final InventoryReconciler reconciler;

    /** @param contents what the frame or stand holds right now */
    public void interacted(OnlinePlayer player, Holder showcase, Supplier<Map<ItemKey, Long>> contents) {
        reconciler.track(player, ViewTarget.container(showcase, contents));
    }

    /** A hit or a break pops the item out of the frame. */
    public void spills(Holder frame, WorldPos at, ItemKey item) {
        contexts.add(at, settings.spillRadius(), new SpawnSource(frame, OriginId.UNKNOWN, Cause.DROP), item);
    }
}

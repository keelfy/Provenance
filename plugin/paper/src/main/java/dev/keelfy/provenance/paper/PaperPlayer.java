package dev.keelfy.provenance.paper;

import dev.keelfy.provenance.app.port.OnlinePlayer;
import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.ItemKey;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.val;
import org.bukkit.GameMode;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.jspecify.annotations.Nullable;

/**
 * A Bukkit player as the application layer sees them.
 *
 * @author keelfy
 */
public record PaperPlayer(Player player) implements OnlinePlayer {

    public static PaperPlayer of(Player player) {
        return new PaperPlayer(player);
    }

    @Override
    public UUID id() {
        return player.getUniqueId();
    }

    @Override
    public String name() {
        return player.getName();
    }

    @Override
    public boolean creative() {
        return player.getGameMode() == GameMode.CREATIVE;
    }

    @Override
    public Map<ItemKey, Long> holdings() {
        val counts = new HashMap<ItemKey, Long>();
        ItemKeys.addAll(counts, player.getInventory().getContents());
        ItemKeys.addAll(counts, player.getEnderChest().getContents());
        ItemKeys.add(counts, player.getItemOnCursor());

        val view = player.getOpenInventory();
        if (Views.target(view).kind().countsTopAsPlayer()) {
            val size = view.getTopInventory().getSize();
            for (int slot = 0; slot < size; slot++) {
                if (view.getSlotType(slot) != InventoryType.SlotType.RESULT) {
                    ItemKeys.add(counts, view.getItem(slot));
                }
            }
        }
        return counts;
    }

    @Override
    public @Nullable ItemKey mainHand() {
        return ItemKeys.of(player.getInventory().getItemInMainHand());
    }

    @Override
    public @Nullable Holder lookingAt(int reach) {
        val target = player.getTargetBlockExact(reach);
        return target != null && target.getState(false) instanceof Container container
                ? Holders.of(container.getInventory())
                : null;
    }
}

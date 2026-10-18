package dev.keelfy.provenance.paper;

import dev.keelfy.provenance.core.ItemKey;
import dev.keelfy.provenance.core.OriginId;
import java.util.HashMap;
import java.util.Map;
import lombok.experimental.UtilityClass;
import lombok.val;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;

/**
 * What the ledger treats as a player's holdings: inventory, ender chest, cursor and workstation input slots.
 *
 * @author keelfy
 */
@UtilityClass
public class Players {

    public Map<ItemKey, Long> counts(Player player) {
        val counts = new HashMap<ItemKey, Long>();
        ItemKeys.addAll(counts, player.getInventory().getContents());
        ItemKeys.addAll(counts, player.getEnderChest().getContents());
        ItemKeys.add(counts, player.getItemOnCursor());

        val view = player.getOpenInventory();
        if (ViewTarget.of(view).kind().countsTopAsPlayer()) {
            val size = view.getTopInventory().getSize();
            for (int slot = 0; slot < size; slot++) {
                if (view.getSlotType(slot) != InventoryType.SlotType.RESULT) {
                    ItemKeys.add(counts, view.getItem(slot));
                }
            }
        }
        return counts;
    }

    /** Origin for items that appear in this player's hands without a modelled source. */
    public OriginId mintOrigin(Player player) {
        return player.getGameMode() == GameMode.CREATIVE ? OriginId.CREATIVE : OriginId.UNKNOWN;
    }

    public OriginId origin(Player player) {
        return OriginId.player(player.getUniqueId());
    }
}

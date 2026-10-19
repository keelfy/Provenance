package dev.keelfy.provenance.paper;

import dev.keelfy.provenance.app.inventory.ViewKind;
import dev.keelfy.provenance.app.inventory.ViewTarget;
import lombok.experimental.UtilityClass;
import lombok.val;
import org.bukkit.inventory.InventoryView;

/**
 * Classifies the top inventory of a Bukkit view for the application layer.
 *
 * @author keelfy
 */
@UtilityClass
public class Views {

    public ViewTarget target(InventoryView view) {
        val top = view.getTopInventory();
        val kind = switch (top.getType()) {
            case CRAFTING, WORKBENCH, ANVIL, GRINDSTONE, SMITHING, STONECUTTER, LOOM, CARTOGRAPHY, ENCHANTING, BEACON ->
                ViewKind.WORKSTATION;
            case MERCHANT -> ViewKind.MERCHANT;
            case PLAYER, CREATIVE, ENDER_CHEST -> ViewKind.SELF;
            default -> null;
        };

        if (kind != null) {
            return new ViewTarget(kind, null, () -> ItemKeys.count(top));
        }

        val holder = Holders.of(top);
        if (holder == null || holder.isPlayer()) {
            return new ViewTarget(ViewKind.SELF, null, () -> ItemKeys.count(top));
        }
        return ViewTarget.container(holder, () -> ItemKeys.count(top));
    }
}

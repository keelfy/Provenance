package dev.keelfy.provenance.paper;

import java.util.Map;
import java.util.function.Supplier;

import org.bukkit.inventory.InventoryView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import dev.keelfy.provenance.core.HolderId;
import dev.keelfy.provenance.core.ItemKey;
import lombok.val;

/**
 * Classified top inventory of a view; {@code container} is set only for {@link ViewKind#CONTAINER}.
 * {@code contents} reads what the top holds right now, so it can be asked again after the interaction.
 *
 * @author keelfy
 */
public record ViewTarget(@NotNull ViewKind kind, @Nullable HolderId container,
                         @NotNull Supplier<Map<ItemKey, Long>> contents) {

    /** Something that keeps items but has no view, e.g. a jukebox or an item frame. */
    public static @NotNull ViewTarget container(@NotNull HolderId holder, @NotNull Supplier<Map<ItemKey, Long>> contents) {
        return new ViewTarget(ViewKind.CONTAINER, holder, contents);
    }

    public static @NotNull ViewTarget of(@NotNull InventoryView view) {
        val top = view.getTopInventory();
        val kind = switch (top.getType()) {
            case CRAFTING, WORKBENCH, ANVIL, GRINDSTONE, SMITHING, STONECUTTER, LOOM, CARTOGRAPHY, ENCHANTING, BEACON -> ViewKind.WORKSTATION;
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
        return container(holder, () -> ItemKeys.count(top));
    }
}

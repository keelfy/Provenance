package dev.keelfy.provenance.paper;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Campfire;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import dev.keelfy.provenance.core.HolderId;
import io.papermc.paper.block.TileStateInventoryHolder;
import lombok.experimental.UtilityClass;

/**
 * Resolves Bukkit inventory owners to ledger holders. A double chest is one holder keyed by its left half.
 *
 * @author keelfy
 */
@UtilityClass
public class Holders {

    public @Nullable HolderId of(@Nullable InventoryHolder holder) {
        return switch (holder) {
            case null -> null;
            case Player player -> HolderId.player(player.getUniqueId());
            case DoubleChest chest -> of(chest.getLeftSide(false));
            case BlockState state -> of(state.getLocation());
            case Entity entity -> HolderId.entity(entity.getUniqueId());
            default -> null;
        };
    }

    public @Nullable HolderId of(@NotNull Inventory inventory) {
        return of(inventory.getHolder(false));
    }

    /** Holder of a block that keeps items: containers (a double chest by its left half), jukebox-like blocks, campfires. */
    public @Nullable HolderId tile(@NotNull BlockState state) {
        return switch (state) {
            case TileStateInventoryHolder tile -> of(tile.getInventory());
            case Campfire campfire -> of(campfire.getBlock());
            default -> null;
        };
    }

    public @NotNull HolderId of(@NotNull Player player) {
        return HolderId.player(player.getUniqueId());
    }

    public @NotNull HolderId of(@NotNull Entity entity) {
        return entity instanceof Player player ? of(player) : HolderId.entity(entity.getUniqueId());
    }

    public @NotNull HolderId of(@NotNull Block block) {
        return HolderId.block(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    public @NotNull HolderId of(@NotNull Location location) {
        return of(location.getBlock());
    }
}

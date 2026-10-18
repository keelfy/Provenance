package dev.keelfy.provenance.paper;

import dev.keelfy.provenance.core.HolderId;
import dev.keelfy.provenance.core.HolderInfo;
import io.papermc.paper.block.TileStateInventoryHolder;
import lombok.experimental.UtilityClass;
import lombok.val;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Nameable;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Campfire;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jspecify.annotations.Nullable;

/**
 * Resolves Bukkit inventory owners to ledger holders. A double chest is one holder keyed by its left half. Blocks and
 * entities carry their type and custom name as of now, which the journal keeps for the web viewer.
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
            case BlockState state -> ofState(state);
            case Entity entity -> of(entity);
            default -> null;
        };
    }

    public @Nullable HolderId of(Inventory inventory) {
        return of(inventory.getHolder(false));
    }

    /**
     * Holder of a block that keeps items: containers (a double chest by its left half), jukebox-like blocks, campfires.
     */
    public @Nullable HolderId tile(BlockState state) {
        return switch (state) {
            case TileStateInventoryHolder tile -> of(tile.getInventory());
            case Campfire campfire -> of(campfire.getBlock());
            default -> null;
        };
    }

    public HolderId of(Player player) {
        return HolderId.player(player.getUniqueId());
    }

    public HolderId of(Entity entity) {
        if (entity instanceof Player player) {
            return of(player);
        }
        return HolderId.entity(entity.getUniqueId())
                .described(info(entity.getType().key(), entity.customName()));
    }

    public HolderId of(Block block) {
        return HolderId.block(block.getWorld().getName(), block.getX(), block.getY(), block.getZ())
                .described(info(block.getType().key(), null));
    }

    /** Same as the block, plus the name given in an anvil if the state has one. */
    public HolderId ofState(BlockState state) {
        val block = HolderId.block(state.getWorld().getName(), state.getX(), state.getY(), state.getZ());
        return block.described(
                info(state.getType().key(), state instanceof Nameable nameable ? nameable.customName() : null));
    }

    public HolderId of(Location location) {
        return of(location.getBlock());
    }

    private HolderInfo info(Key type, @Nullable Component customName) {
        return new HolderInfo(
                type.asString(),
                customName != null ? PlainTextComponentSerializer.plainText().serialize(customName) : null);
    }
}

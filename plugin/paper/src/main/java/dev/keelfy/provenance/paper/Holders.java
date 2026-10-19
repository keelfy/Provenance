package dev.keelfy.provenance.paper;

import dev.keelfy.provenance.core.model.Chunk;
import dev.keelfy.provenance.core.model.Holder;
import io.papermc.paper.block.TileStateInventoryHolder;
import lombok.experimental.UtilityClass;
import lombok.val;
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

    public @Nullable Holder of(@Nullable InventoryHolder holder) {
        return switch (holder) {
            case null -> null;
            case Player player -> Holder.player(player.getUniqueId());
            case DoubleChest chest -> of(chest.getLeftSide(false));
            case BlockState state -> ofState(state);
            case Entity entity -> of(entity);
            default -> null;
        };
    }

    public @Nullable Holder of(Inventory inventory) {
        return of(inventory.getHolder(false));
    }

    /**
     * Holder of a block that keeps items: containers (a double chest by its left half), jukebox-like blocks, campfires.
     */
    public @Nullable Holder tile(BlockState state) {
        return switch (state) {
            case TileStateInventoryHolder tile -> of(tile.getInventory());
            case Campfire campfire -> of(campfire.getBlock());
            default -> null;
        };
    }

    public Holder of(Player player) {
        return Holder.player(player.getUniqueId());
    }

    public Holder of(Entity entity) {
        if (entity instanceof Player player) {
            return of(player);
        }
        val at = entity.getLocation();
        return Holder.entity(entity.getUniqueId())
                .described(
                        entity.getType().key().asString(),
                        label(entity.customName()),
                        Chunk.ofBlock(entity.getWorld().getName(), at.getBlockX(), at.getBlockZ()));
    }

    public Holder of(Block block) {
        return Holder.block(block.getWorld().getName(), block.getX(), block.getY(), block.getZ())
                .described(block.getType().key().asString());
    }

    /** Same as the block, plus the name given in an anvil if the state has one. */
    public Holder ofState(BlockState state) {
        val block = Holder.block(state.getWorld().getName(), state.getX(), state.getY(), state.getZ());
        return block.described(
                state.getType().key().asString(),
                label(state instanceof Nameable nameable ? nameable.customName() : null));
    }

    public Holder of(Location location) {
        return of(location.getBlock());
    }

    private @Nullable String label(@Nullable Component customName) {
        return customName != null ? PlainTextComponentSerializer.plainText().serialize(customName) : null;
    }
}

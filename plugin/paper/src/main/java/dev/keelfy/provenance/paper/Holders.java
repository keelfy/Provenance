package dev.keelfy.provenance.paper;

import dev.keelfy.provenance.core.model.Chunk;
import dev.keelfy.provenance.core.model.Holder;
import io.papermc.paper.block.TileStateInventoryHolder;
import io.papermc.paper.persistence.PersistentDataContainerView;
import java.util.List;
import java.util.UUID;
import lombok.experimental.UtilityClass;
import lombok.val;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Nameable;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Campfire;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

/**
 * Resolves Bukkit inventory owners to ledger holders. A double chest is one holder keyed by its left half. Blocks and
 * entities carry their type and custom name as of now, which the journal keeps for the web viewer. A shulker box is
 * known by an id kept in its block data and carried over to its item when it is broken: placed again elsewhere, it is
 * the same box.
 *
 * @author keelfy
 */
@UtilityClass
public class Holders {

    private static final NamespacedKey SHULKER_ID = new NamespacedKey("provenance", "shulker");

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
        if (Tag.SHULKER_BOXES.isTagged(block.getType())) {
            return ofState(block.getState(false));
        }
        return Holder.block(block.getWorld().getName(), block.getX(), block.getY(), block.getZ())
                .described(block.getType().key().asString());
    }

    /** Same as the block, plus the name given in an anvil if the state has one. */
    public Holder ofState(BlockState state) {
        val label = label(state instanceof Nameable nameable ? nameable.customName() : null);
        val id = state instanceof ShulkerBox box ? shulkerId(box.getPersistentDataContainer()) : null;
        if (id != null) {
            return Holder.shulker(id)
                    .described(
                            state.getType().key().asString(),
                            label,
                            Chunk.ofBlock(state.getWorld().getName(), state.getX(), state.getZ()));
        }
        val block = Holder.block(state.getWorld().getName(), state.getX(), state.getY(), state.getZ());
        return block.described(state.getType().key().asString(), label);
    }

    /** A placed shulker box takes the id its item brought along, or a new one if it is new to us. */
    public void mark(Block block, ItemStack placed) {
        if (!(block.getState() instanceof ShulkerBox box)) {
            return;
        }
        val carried = shulkerId(placed.getPersistentDataContainer());
        val id = carried != null ? carried : UUID.randomUUID();
        box.getPersistentDataContainer().set(SHULKER_ID, PersistentDataType.STRING, id.toString());
        box.update(false, false);
    }

    /** The server drops a broken shulker box without its block data, so its id is carried over to the item here. */
    public void carry(BlockState broken, List<Item> drops) {
        if (!(broken instanceof ShulkerBox box)) {
            return;
        }
        val id = shulkerId(box.getPersistentDataContainer());
        if (id == null) {
            return;
        }
        for (val drop : drops) {
            val stack = drop.getItemStack();
            if (Tag.SHULKER_BOXES.isTagged(stack.getType())) {
                stack.editPersistentDataContainer(
                        data -> data.set(SHULKER_ID, PersistentDataType.STRING, id.toString()));
                drop.setItemStack(stack);
                return;
            }
        }
    }

    /** {@code null} if there is no id, or it is not one of ours. */
    private @Nullable UUID shulkerId(PersistentDataContainerView data) {
        val id = data.get(SHULKER_ID, PersistentDataType.STRING);
        try {
            return id != null ? UUID.fromString(id) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public Holder of(Location location) {
        return of(location.getBlock());
    }

    private @Nullable String label(@Nullable Component customName) {
        return customName != null ? PlainTextComponentSerializer.plainText().serialize(customName) : null;
    }
}

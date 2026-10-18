package dev.keelfy.provenance.paper;

import java.util.HashMap;
import java.util.Map;

import org.bukkit.block.BlockState;
import org.bukkit.block.Campfire;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import dev.keelfy.provenance.core.ItemKey;
import io.papermc.paper.block.TileStateInventoryHolder;
import io.papermc.paper.datacomponent.DataComponentTypes;
import lombok.experimental.UtilityClass;
import lombok.val;

/**
 * Maps Bukkit stacks to ledger item keys. MVP fungibility class is the item type; components are ignored,
 * except that what a stack carries inside (shulker boxes, bundles) counts as items of its holder.
 *
 * @author keelfy
 */
@UtilityClass
public class ItemKeys {

    private static final EquipmentSlot[] STAND_SLOTS = {
            EquipmentSlot.HAND, 
            EquipmentSlot.OFF_HAND,
            EquipmentSlot.HEAD, 
            EquipmentSlot.CHEST, 
            EquipmentSlot.LEGS, 
            EquipmentSlot.FEET
    };

    public @Nullable ItemKey of(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return new ItemKey(stack.getType().getKey().toString());
    }

    public void add(@NotNull Map<ItemKey, Long> counts, @Nullable ItemStack stack) {
        add(counts, stack, 1);
    }

    public void addAll(@NotNull Map<ItemKey, Long> counts, @Nullable ItemStack @NotNull [] stacks) {
        for (val stack : stacks) {
            add(counts, stack);
        }
    }

    /** The stack together with everything it carries inside. */
    public @NotNull Map<ItemKey, Long> count(@NotNull ItemStack stack) {
        val counts = new HashMap<ItemKey, Long>();
        add(counts, stack);
        return counts;
    }

    /** Only what the stack carries inside, e.g. the contents of a shulker box that is about to be placed. */
    public @NotNull Map<ItemKey, Long> inside(@NotNull ItemStack stack) {
        val counts = new HashMap<ItemKey, Long>();
        addInside(counts, stack, stack.getAmount());
        return counts;
    }

    public @NotNull Map<ItemKey, Long> count(@NotNull Inventory inventory) {
        val counts = new HashMap<ItemKey, Long>();
        addAll(counts, inventory.getContents());
        return counts;
    }

    /** Items kept by a block without a view: containers, jukebox-like blocks and campfires; empty for any other block. */
    public @NotNull Map<ItemKey, Long> count(@NotNull BlockState state) {
        return switch (state) {
            case TileStateInventoryHolder tile -> count(tile.getInventory());
            case Campfire campfire -> countCampfire(campfire);
            default -> Map.of();
        };
    }

    private @NotNull Map<ItemKey, Long> countCampfire(@NotNull Campfire campfire) {
        val counts = new HashMap<ItemKey, Long>();
        for (int slot = 0; slot < campfire.getSize(); slot++) {
            add(counts, campfire.getItem(slot));
        }
        return counts;
    }

    public @NotNull Map<ItemKey, Long> count(@NotNull ItemFrame frame) {
        return count(frame.getItem());
    }

    public @NotNull Map<ItemKey, Long> count(@NotNull ArmorStand stand) {
        val counts = new HashMap<ItemKey, Long>();
        val equipment = stand.getEquipment();
        for (val slot : STAND_SLOTS) {
            add(counts, equipment.getItem(slot));
        }
        return counts;
    }

    private void add(@NotNull Map<ItemKey, Long> counts, @Nullable ItemStack stack, long times) {
        val key = of(stack);
        if (key == null) {
            return;
        }

        val amount = stack.getAmount() * times;
        counts.merge(key, amount, (a, b) -> a + b);
        addInside(counts, stack, amount);
    }

    private void addInside(@NotNull Map<ItemKey, Long> counts, @NotNull ItemStack stack, long times) {
        val container = stack.getData(DataComponentTypes.CONTAINER);
        if (container != null) {
            container.contents().forEach(inner -> add(counts, inner, times));
        }

        val bundle = stack.getData(DataComponentTypes.BUNDLE_CONTENTS);
        if (bundle != null) {
            bundle.contents().forEach(inner -> add(counts, inner, times));
        }
    }
}

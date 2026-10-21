package dev.keelfy.provenance.paper;

import dev.keelfy.provenance.core.model.ItemKey;
import io.papermc.paper.block.TileStateInventoryHolder;
import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import lombok.experimental.UtilityClass;
import lombok.val;
import org.bukkit.block.BlockState;
import org.bukkit.block.Campfire;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Maps Bukkit stacks to ledger item keys. The fungibility class is the item type plus a hash of its data components
 * ({@code minecraft:diamond_sword#3f9a…}), so an enchanted or renamed stack is its own pool; a stack without components
 * keeps the bare type. Components that change in place without an inventory event (wear, contents, a loaded crossbow, a
 * book being written) are left out: otherwise using an item would burn it and mint it back as {@code UNKNOWN}. What a
 * stack carries inside (shulker boxes, bundles) counts as items of its holder.
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

    private static final Set<DataComponentType> MUTABLE = Set.of(
            DataComponentTypes.DAMAGE,
            DataComponentTypes.CONTAINER,
            DataComponentTypes.BUNDLE_CONTENTS,
            DataComponentTypes.CHARGED_PROJECTILES,
            DataComponentTypes.WRITABLE_BOOK_CONTENT,
            DataComponentTypes.LODESTONE_TRACKER);

    /** Hex digits of the component hash kept in the key: 64 bits, collisions are not a concern. */
    private static final int VARIANT_LENGTH = 16;

    public @Nullable ItemKey of(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }

        val type = stack.getType().getKey().toString();
        val variant = variant(stack);
        return new ItemKey(variant == null ? type : type + "#" + variant);
    }

    /** Hash of the components that set the stack apart from a plain one of its type; {@code null} if none do. */
    private @Nullable String variant(ItemStack stack) {
        if (!stack.hasItemMeta()) {
            return null;
        }

        val copy = stack.asOne();
        MUTABLE.forEach(copy::resetData);
        if (copy.isSimilar(ItemStack.of(copy.getType()))) {
            return null;
        }
        return hash(copy.getItemMeta().getAsComponentString());
    }

    private String hash(String components) {
        try {
            val digest = MessageDigest.getInstance("SHA-256").digest(components.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, VARIANT_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }

    public void add(Map<ItemKey, Long> counts, @Nullable ItemStack stack) {
        add(counts, stack, 1);
    }

    public void addAll(Map<ItemKey, Long> counts, @Nullable ItemStack[] stacks) {
        for (val stack : stacks) {
            add(counts, stack);
        }
    }

    /** The stack together with everything it carries inside. */
    public Map<ItemKey, Long> count(ItemStack stack) {
        val counts = new HashMap<ItemKey, Long>();
        add(counts, stack);
        return counts;
    }

    /** Only what the stack carries inside, e.g. the contents of a shulker box that is about to be placed. */
    public Map<ItemKey, Long> inside(ItemStack stack) {
        val counts = new HashMap<ItemKey, Long>();
        addInside(counts, stack, stack.getAmount());
        return counts;
    }

    public Map<ItemKey, Long> count(Inventory inventory) {
        val counts = new HashMap<ItemKey, Long>();
        addAll(counts, inventory.getContents());
        return counts;
    }

    /**
     * Items kept by a block without a view: containers, jukebox-like blocks and campfires; empty for any other block.
     */
    public Map<ItemKey, Long> count(BlockState state) {
        return switch (state) {
            case TileStateInventoryHolder tile -> count(tile.getInventory());
            case Campfire campfire -> countCampfire(campfire);
            default -> Map.of();
        };
    }

    private Map<ItemKey, Long> countCampfire(Campfire campfire) {
        val counts = new HashMap<ItemKey, Long>();
        for (int slot = 0; slot < campfire.getSize(); slot++) {
            add(counts, campfire.getItem(slot));
        }
        return counts;
    }

    public Map<ItemKey, Long> count(ItemFrame frame) {
        return count(frame.getItem());
    }

    public Map<ItemKey, Long> count(ArmorStand stand) {
        val counts = new HashMap<ItemKey, Long>();
        val equipment = stand.getEquipment();
        for (val slot : STAND_SLOTS) {
            add(counts, equipment.getItem(slot));
        }
        return counts;
    }

    private void add(Map<ItemKey, Long> counts, @Nullable ItemStack stack, long times) {
        val key = of(stack);
        if (stack == null || key == null) {
            return;
        }

        val amount = stack.getAmount() * times;
        counts.merge(key, amount, (a, b) -> a + b);
        addInside(counts, stack, amount);
    }

    private void addInside(Map<ItemKey, Long> counts, ItemStack stack, long times) {
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

package dev.keelfy.provenance.paper;

import dev.keelfy.provenance.app.world.ItemDrop;
import dev.keelfy.provenance.app.world.WorldPos;
import lombok.experimental.UtilityClass;
import lombok.val;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;

/**
 * Bukkit places and item entities as the application layer's values.
 *
 * @author keelfy
 */
@UtilityClass
public class Positions {

    public WorldPos of(Location location) {
        return new WorldPos(location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
    }

    public WorldPos center(Block block) {
        return of(block.getLocation().toCenterLocation());
    }

    public ItemDrop drop(Item item) {
        val stack = item.getItemStack();
        return new ItemDrop(Holders.of(item), of(item.getLocation()), ItemKeys.of(stack), ItemKeys.count(stack));
    }
}

package dev.keelfy.provenance.paper.world;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import dev.keelfy.provenance.core.ItemKey;
import dev.keelfy.provenance.paper.SpawnSource;
import lombok.val;

/**
 * Short-lived spatial hints attributing item entities spawned this tick (death drops, container spills).
 *
 * @author keelfy
 */
public final class SpawnContexts {

    private final @NotNull Plugin plugin;
    private final @NotNull List<Entry> entries;

    public SpawnContexts(@NotNull Plugin plugin) {
        this.plugin = plugin;
        this.entries = new ArrayList<>();
    }

    private record Entry(UUID world, double x, double y, double z, double radiusSquared, SpawnSource source,
                         @Nullable ItemKey item) {
    }

    /** Registers a hint valid until the end of the current tick. */
    public void add(@NotNull Location location, double radius, @NotNull SpawnSource source) {
        add(location, radius, source, null);
    }

    /** Like {@link #add(Location, double, SpawnSource)} but for one entity of {@code item}: it is used up by the first match. */
    public void add(@NotNull Location location, double radius, @NotNull SpawnSource source, @Nullable ItemKey item) {
        if (entries.isEmpty()) {
            plugin.getServer().getScheduler().runTask(plugin, entries::clear);
        }
        entries.add(new Entry(location.getWorld().getUID(), location.getX(), location.getY(), location.getZ(),
                radius * radius, source, item));
    }

    /** Closest hint covering {@code location} that is not meant for another item. */
    public @Nullable SpawnSource find(@NotNull Location location, @Nullable ItemKey item) {
        Entry best = null;
        double bestDistance = Double.MAX_VALUE;
        for (val entry : entries) {
            if (!entry.world().equals(location.getWorld().getUID())
                    || entry.item() != null && !entry.item().equals(item)) {
                continue;
            }
            val dx = entry.x() - location.getX();
            val dy = entry.y() - location.getY();
            val dz = entry.z() - location.getZ();
            val distance = dx * dx + dy * dy + dz * dz;
            if (distance <= entry.radiusSquared() && distance < bestDistance) {
                best = entry;
                bestDistance = distance;
            }
        }
        if (best == null) {
            return null;
        }
        if (best.item() != null) {
            entries.remove(best);
        }
        return best.source();
    }
}

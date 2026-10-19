package dev.keelfy.provenance.app.world;

import dev.keelfy.provenance.app.port.Scheduler;
import dev.keelfy.provenance.core.model.ItemKey;
import java.util.ArrayList;
import java.util.List;
import lombok.val;
import org.jspecify.annotations.Nullable;

/**
 * Short-lived spatial hints attributing item entities spawned this tick (death drops, container spills).
 *
 * @author keelfy
 */
public final class SpawnContexts {

    private final Scheduler scheduler;
    private final List<Entry> entries;

    public SpawnContexts(Scheduler scheduler) {
        this.scheduler = scheduler;
        this.entries = new ArrayList<>();
    }

    private record Entry(
            String world,
            double x,
            double y,
            double z,
            double radiusSquared,
            SpawnSource source,
            @Nullable ItemKey item) {}

    /** Registers a hint valid until the end of the current tick. */
    public void add(WorldPos at, double radius, SpawnSource source) {
        add(at, radius, source, null);
    }

    /**
     * Like {@link #add(WorldPos, double, SpawnSource)} but for one entity of {@code item}: it is used up by the first
     * match.
     */
    public void add(WorldPos at, double radius, SpawnSource source, @Nullable ItemKey item) {
        if (entries.isEmpty()) {
            scheduler.runNextTick(entries::clear);
        }
        entries.add(new Entry(at.world(), at.x(), at.y(), at.z(), radius * radius, source, item));
    }

    /** Closest hint covering {@code at} that is not meant for another item. */
    public @Nullable SpawnSource find(WorldPos at, @Nullable ItemKey item) {
        Entry best = null;
        double bestDistance = Double.MAX_VALUE;
        for (val entry : entries) {
            if (!entry.world().equals(at.world())
                    || entry.item() != null && !entry.item().equals(item)) {
                continue;
            }
            val dx = entry.x() - at.x();
            val dy = entry.y() - at.y();
            val dz = entry.z() - at.z();
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

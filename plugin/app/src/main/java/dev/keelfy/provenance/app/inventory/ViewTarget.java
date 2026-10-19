package dev.keelfy.provenance.app.inventory;

import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.ItemKey;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Classified top inventory of a view; {@code container} is set only for {@link ViewKind#CONTAINER}. {@code contents}
 * reads what the top holds right now, so it can be asked again after the interaction.
 *
 * @author keelfy
 */
public record ViewTarget(ViewKind kind, @Nullable Holder container, Supplier<Map<ItemKey, Long>> contents) {

    /** Something that keeps items but has no view, e.g. a jukebox or an item frame. */
    public static ViewTarget container(@Nullable Holder holder, Supplier<Map<ItemKey, Long>> contents) {
        return new ViewTarget(ViewKind.CONTAINER, holder, contents);
    }
}

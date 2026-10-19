package dev.keelfy.provenance.app.world;

import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.ItemKey;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A dropped item entity: where it is, the stack's item and everything the stack counts as (its own item plus what it
 * carries inside).
 *
 * @author keelfy
 */
public record ItemDrop(Holder entity, WorldPos at, @Nullable ItemKey key, Map<ItemKey, Long> counts) {}

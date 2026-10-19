package dev.keelfy.provenance.app.observe;

import dev.keelfy.provenance.core.model.ItemKey;

/**
 * One stack: an item and how many.
 *
 * @author keelfy
 */
public record ItemAmount(ItemKey item, long amount) {}

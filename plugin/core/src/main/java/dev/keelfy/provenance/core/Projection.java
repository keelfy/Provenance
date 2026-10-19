package dev.keelfy.provenance.core;

import dev.keelfy.provenance.core.event.LedgerEvent;

/**
 * A read model folded from the event stream. Applying events is the only way it changes, live and on replay alike.
 *
 * @author keelfy
 */
interface Projection {

    void apply(LedgerEvent event);
}

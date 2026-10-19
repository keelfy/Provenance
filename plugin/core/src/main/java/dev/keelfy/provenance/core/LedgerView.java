package dev.keelfy.provenance.core;

import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.ItemKey;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The read side of a {@link Ledger}: what is tracked where and who handed what to whom.
 *
 * @author keelfy
 */
public interface LedgerView {

    @Nullable Pool pool(Holder holder, ItemKey item);

    long getCount(Holder holder, ItemKey item);

    Map<ItemKey, Pool> getPools(Holder holder);

    Set<Holder> getHolders();

    FlowGraph getFlows();
}

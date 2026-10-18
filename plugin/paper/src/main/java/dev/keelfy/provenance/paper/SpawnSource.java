package dev.keelfy.provenance.paper;

import dev.keelfy.provenance.core.Cause;
import dev.keelfy.provenance.core.HolderId;
import dev.keelfy.provenance.core.ItemKey;
import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.core.OriginId;
import lombok.val;
import org.jspecify.annotations.Nullable;

/**
 * Where freshly spawned items come from: taken out of {@code from} while it has them, otherwise minted as
 * {@code fallback}.
 *
 * @author keelfy
 */
public record SpawnSource(@Nullable HolderId from, OriginId fallback, Cause cause) {

    @SuppressWarnings("null")
    public void apply(Ledger ledger, HolderId target, ItemKey item, long amount) {
        val moved = from == null ? 0 : Math.min(amount, ledger.getCount(from, item));
        if (moved > 0) {
            ledger.transfer(cause, from, target, item, moved);
        }
        ledger.mint(cause, target, item, amount - moved, fallback);
    }
}

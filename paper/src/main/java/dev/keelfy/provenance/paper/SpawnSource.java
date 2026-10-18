package dev.keelfy.provenance.paper;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import dev.keelfy.provenance.core.HolderId;
import dev.keelfy.provenance.core.ItemKey;
import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.core.OriginId;
import lombok.val;

/**
 * Where freshly spawned items come from: taken out of {@code from} while it has them, otherwise minted as {@code fallback}.
 *
 * @author keelfy
 */
public record SpawnSource(@Nullable HolderId from, @NotNull OriginId fallback) {

    public void apply(@NotNull Ledger ledger, @NotNull HolderId target, @NotNull ItemKey item, long amount) {
        val moved = from == null ? 0 : Math.min(amount, ledger.getCount(from, item));
        if (moved > 0) {
            ledger.transfer(from, target, item, moved);
        }
        ledger.mint(target, item, amount - moved, fallback);
    }
}

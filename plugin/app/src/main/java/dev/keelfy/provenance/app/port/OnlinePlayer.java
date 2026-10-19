package dev.keelfy.provenance.app.port;

import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.ItemKey;
import dev.keelfy.provenance.core.model.OriginId;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A player on the server, as far as the plugin needs to ask the game about them. The adapter reads the game on every
 * call, so answers are current as of that call.
 *
 * @author keelfy
 */
public interface OnlinePlayer {

    UUID id();

    String name();

    boolean creative();

    /**
     * What the ledger treats as the player's holdings: inventory, ender chest, cursor and workstation input slots. A
     * new, modifiable map on every call.
     */
    Map<ItemKey, Long> holdings();

    /** The item in the main hand, {@code null} if it is empty. */
    @Nullable ItemKey mainHand();

    /** The container the player looks at within {@code reach} blocks. */
    @Nullable Holder lookingAt(int reach);

    default Holder holder() {
        return Holder.player(id());
    }

    default OriginId origin() {
        return OriginId.player(id());
    }

    /** Origin for items that appear in this player's hands without a modelled source. */
    default OriginId mintOrigin() {
        return creative() ? OriginId.CREATIVE : OriginId.UNKNOWN;
    }
}

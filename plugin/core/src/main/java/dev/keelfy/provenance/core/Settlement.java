package dev.keelfy.provenance.core;

import dev.keelfy.provenance.core.model.Cause;
import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.ItemKey;
import dev.keelfy.provenance.core.model.OriginId;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.val;

/**
 * Turns observed inventory counts into ledger operations. Callers reconcile holders before the interaction, so any
 * remaining delta afterwards is the interaction's effect.
 *
 * @author keelfy
 */
@RequiredArgsConstructor
public final class Settlement {

    private final Ledger ledger;
    private final Cause cause;

    /** Attribution of items that appear with no explanation. */
    private final OriginId mintOrigin;

    /**
     * Player looking into a real container: opposite count changes of the same item are transfers, the rest is
     * reconciled with {@code mintOrigin}.
     */
    public void container(
            Holder player, Map<ItemKey, Long> playerActual, Holder container, Map<ItemKey, Long> containerActual) {
        val playerDelta = ledger.delta(player, playerActual);
        val containerDelta = ledger.delta(container, containerActual);

        playerDelta.forEach((item, dp) -> {
            val dc = containerDelta.getOrDefault(item, 0L);
            if (dp < 0 && dc > 0) {
                ledger.transfer(cause, player, container, item, Math.min(-dp, dc));
            } else if (dp > 0 && dc < 0) {
                ledger.transfer(cause, container, player, item, Math.min(dp, -dc));
            }
        });

        ledger.reconcile(cause, player, playerActual, mintOrigin);
        ledger.reconcile(cause, container, containerActual, mintOrigin);
    }

    /**
     * Player using a crafting grid or workstation, counted as part of the player: what disappeared is the input, what
     * appeared is the output and inherits the inputs' origin. Items that appear with no input at all (creative menu,
     * pickups while the player's own crafting view is open) are not crafted: they get {@code mintOrigin}.
     */
    public void workstation(Holder player, Map<ItemKey, Long> playerActual) {
        val inputs = new HashMap<ItemKey, Long>();
        val outputs = new HashMap<ItemKey, Long>();

        ledger.delta(player, playerActual).forEach((item, diff) -> {
            if (diff < 0) {
                inputs.put(item, -diff);
            } else {
                outputs.put(item, diff);
            }
        });

        if (outputs.isEmpty()) {
            inputs.forEach((item, amount) -> ledger.burn(cause, player, item, amount));
        } else if (inputs.isEmpty()) {
            outputs.forEach((item, amount) -> ledger.mint(cause, player, item, amount, mintOrigin));
        } else {
            ledger.convert(cause, player, inputs, outputs);
        }
    }
}

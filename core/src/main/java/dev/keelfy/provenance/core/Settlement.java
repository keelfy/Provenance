package dev.keelfy.provenance.core;

import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import lombok.experimental.UtilityClass;
import lombok.val;

/**
 * Turns observed inventory counts into ledger operations. Callers reconcile holders before the interaction,
 * so any remaining delta afterwards is the interaction's effect.
 *
 * @author keelfy
 */
@UtilityClass
public class Settlement {

    /**
     * Player looking into a real container: opposite count changes of the same item are transfers,
     * the rest is reconciled with {@code mintOrigin}.
     */
    public void container(@NotNull Ledger ledger,
                          @NotNull HolderId player, @NotNull Map<ItemKey, Long> playerActual,
                          @NotNull HolderId container, @NotNull Map<ItemKey, Long> containerActual,
                          @NotNull OriginId mintOrigin) {
        val playerDelta = ledger.delta(player, playerActual);
        val containerDelta = ledger.delta(container, containerActual);

        playerDelta.forEach((item, dp) -> {
            val dc = containerDelta.getOrDefault(item, 0L);
            if (dp < 0 && dc > 0) {
                ledger.transfer(player, container, item, Math.min(-dp, dc));
            } else if (dp > 0 && dc < 0) {
                ledger.transfer(container, player, item, Math.min(dp, -dc));
            }
        });

        ledger.reconcile(player, playerActual, mintOrigin);
        ledger.reconcile(container, containerActual, mintOrigin);
    }

    /**
     * Player using a crafting grid or workstation, counted as part of the player: what disappeared is the input,
     * what appeared is the output and inherits the inputs' origin. Items that appear with no input at all
     * (creative menu, pickups while the player's own crafting view is open) are not crafted: they get {@code mintOrigin}.
     */
    public void workstation(@NotNull Ledger ledger, @NotNull HolderId player, @NotNull Map<ItemKey, Long> playerActual,
                            @NotNull OriginId mintOrigin) {
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
            inputs.forEach((item, amount) -> ledger.burn(player, item, amount));
        } else if (inputs.isEmpty()) {
            outputs.forEach((item, amount) -> ledger.mint(player, item, amount, mintOrigin));
        } else {
            ledger.convert(player, inputs, outputs);
        }
    }
}

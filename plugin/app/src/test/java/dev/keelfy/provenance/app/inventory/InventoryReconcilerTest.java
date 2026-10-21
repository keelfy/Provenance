package dev.keelfy.provenance.app.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import dev.keelfy.provenance.app.Fakes;
import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.core.model.Cause;
import dev.keelfy.provenance.core.model.ItemKey;
import dev.keelfy.provenance.core.model.OriginId;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InventoryReconcilerTest {

    private static final ItemKey DIAMOND = new ItemKey("minecraft:diamond");

    private final Ledger ledger = new Ledger(() -> 0);
    private final Fakes.Player alice = new Fakes.Player();
    private final Set<UUID> syncing = new HashSet<>();
    private final InventoryReconciler reconciler = new InventoryReconciler(
            new Fakes.ManualScheduler(), Fakes.directory(alice), ledger, Fakes.settings(), syncing::contains);

    @Test
    void aSyncedInventoryIsNeitherMintedNorBurnedUntilTheSyncIsDone() {
        reconciler.reconcile(alice); // looked into when they joined
        ledger.mint(Cause.MINE, alice.holder(), DIAMOND, 3, alice.origin());
        syncing.add(alice.id());

        // the inventory is still empty (burn) and then full of items that came from elsewhere (mint)
        reconciler.reconcile(alice);
        alice.items.put(DIAMOND, 5L);
        reconciler.reconcile(alice);

        assertThat(ledger.getCount(alice.holder(), DIAMOND)).isEqualTo(3);

        syncing.clear();
        reconciler.reconcile(alice);

        assertThat(ledger.getCount(alice.holder(), DIAMOND)).isEqualTo(5);
        assertThat(ledger.pool(alice.holder(), DIAMOND).getOrigin().share(OriginId.UNKNOWN))
                .isEqualTo(2.0 / 5);
    }

    @Test
    void finalReconcileIgnoresTheSync() {
        syncing.add(alice.id());
        alice.items.put(DIAMOND, 2L);

        reconciler.reconcileFinal(alice);

        assertThat(ledger.getCount(alice.holder(), DIAMOND)).isEqualTo(2);
    }
}

package dev.keelfy.provenance.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.core.event.Envelope;
import dev.keelfy.provenance.core.event.LedgerEvent;
import lombok.val;
import org.junit.jupiter.api.Test;

class HolderTest {

    private static final ItemKey DIAMOND = new ItemKey("minecraft:diamond");

    private final Holder chest = Holder.block("world", 1, 64, 2);

    @Test
    void identityIgnoresDescription() {
        val chestNow = chest.described("minecraft:chest", "Loot");
        val furnaceNow = chest.described("minecraft:furnace");

        assertThat(chestNow).isEqualTo(chest).isEqualTo(furnaceNow);
        assertThat(chestNow).hasSameHashCodeAs(furnaceNow);
        assertThat(Holder.block("world", 1, 64, 3)).isNotEqualTo(chest);
    }

    @Test
    void ledgerKeepsOneAccountPerHolderWhateverItIsCalled() {
        val ledger = new Ledger();
        ledger.mint(Cause.CONTAINER, chest.described("minecraft:chest"), DIAMOND, 5, OriginId.UNKNOWN);
        ledger.mint(Cause.CONTAINER, chest.described("minecraft:furnace"), DIAMOND, 2, OriginId.UNKNOWN);

        assertThat(ledger.getHolders()).hasSize(1);
        assertThat(ledger.getCount(chest, DIAMOND)).isEqualTo(7);
    }

    @Test
    void replayingClearOfAnUnknownHolderIsHarmless() {
        val ledger = new Ledger();

        assertThatCode(() -> ledger.replay(new Envelope(1, 1000, Cause.CONTAINER, new LedgerEvent.Cleared(chest))))
                .doesNotThrowAnyException();
    }
}

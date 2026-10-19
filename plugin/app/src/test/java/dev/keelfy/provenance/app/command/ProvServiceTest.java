package dev.keelfy.provenance.app.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.keelfy.provenance.app.Fakes;
import dev.keelfy.provenance.app.inventory.InventoryReconciler;
import dev.keelfy.provenance.app.port.OnlinePlayer;
import dev.keelfy.provenance.app.port.PlayerDirectory;
import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.core.event.Envelope;
import dev.keelfy.provenance.core.event.LedgerEvent.Minted;
import dev.keelfy.provenance.core.model.Cause;
import dev.keelfy.provenance.core.model.ItemKey;
import dev.keelfy.provenance.core.model.OriginId;
import java.util.ArrayList;
import java.util.List;
import lombok.val;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ProvServiceTest {

    private static final ItemKey DIAMOND = new ItemKey("minecraft:diamond");

    private final Fakes.ManualScheduler scheduler = new Fakes.ManualScheduler();
    private final Ledger ledger = new Ledger(() -> 0);
    private final Fakes.Player alice = new Fakes.Player();
    private final PlayerDirectory directory = Fakes.directory(alice);
    private final ProvService service = new ProvService(
            ledger,
            new InventoryReconciler(scheduler, directory, ledger, Fakes.settings()),
            null,
            null,
            directory,
            scheduler,
            null,
            Fakes.settings());

    private final List<String> console = new ArrayList<>();
    private final Sender consoleSender = new Sender() {
        @Override
        public void send(Line line) {
            console.add(line.spans().stream().map(Span::text).reduce("", String::concat));
        }

        @Override
        public @Nullable OnlinePlayer player() {
            return null;
        }
    };

    @Test
    void handIsForPlayersOnly() {
        assertThat(service.hand(consoleSender)).isFalse();
        assertThat(console).containsExactly("Players only");
    }

    @Test
    void unknownPlayersAreReported() {
        assertThat(service.player(consoleSender, "Nobody")).isFalse();
        assertThat(console).containsExactly("Unknown player");
    }

    @Test
    void playerListsWhatTheyHoldByOrigin() {
        ledger.mint(Cause.MINE, alice.holder(), DIAMOND, 4, OriginId.player(alice.id()));

        assertThat(service.player(consoleSender, "Alice")).isTrue();
        assertThat(console).first().isEqualTo("Alice holds, by origin:");
        assertThat(console).last().isEqualTo("  share: Alice 100%");
    }

    @Test
    void linksNeedsAPlayerWhenRunFromTheConsole() {
        assertThat(service.links(consoleSender, null)).isFalse();
        assertThat(console).containsExactly("Name a player");
    }

    @Test
    void historyRowsShowSuspiciousMintsAsAlerts() {
        val labels = new Labels(directory);
        val envelope = new Envelope(1, 0, Cause.MINE, new Minted(alice.holder(), DIAMOND, 3, OriginId.UNKNOWN));

        val line = new HistoryView(labels).line(envelope, 120_000, 3);

        assertThat(line.spans()).extracting(Span::tone).containsExactly(Tone.DIM, Tone.ALERT);
        assertThat(line.spans().get(0).text()).isEqualTo("2m mine ");
        assertThat(line.spans().get(1).text()).isEqualTo("+3 diamond into Alice (unknown)");
    }
}

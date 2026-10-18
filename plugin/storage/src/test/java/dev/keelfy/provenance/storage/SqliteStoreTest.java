package dev.keelfy.provenance.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import dev.keelfy.provenance.core.Cause;
import dev.keelfy.provenance.core.Envelope;
import dev.keelfy.provenance.core.HolderId;
import dev.keelfy.provenance.core.ItemKey;
import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.core.LedgerChanges;
import dev.keelfy.provenance.core.LedgerEvent;
import dev.keelfy.provenance.core.OriginId;
import dev.keelfy.provenance.core.TaintVector;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteStoreTest {

    private static final Cause CAUSE = Cause.CONTAINER;
    private static final Retention RETENTION = new Retention(Duration.ofDays(30), Duration.ofDays(3));
    private static final ItemKey DIAMOND = new ItemKey("minecraft:diamond");

    @TempDir
    Path dir;

    private final OriginId alice = OriginId.player(UUID.randomUUID());
    private final HolderId bob = HolderId.player(UUID.randomUUID());
    private final HolderId chest = HolderId.block("world", 1, 64, 2);

    @Test
    void roundTripKeepsPoolsVectorsAndFlows() throws Exception {
        val ledger = new Ledger();
        ledger.mint(CAUSE, chest, DIAMOND, 10, alice);
        ledger.mint(CAUSE, chest, DIAMOND, 10, OriginId.MOB);
        ledger.transfer(CAUSE, chest, bob, DIAMOND, 5);

        try (val store = new SqliteStore(dir.resolve("a.db"), RETENTION)) {
            store.write(ledger.drainChanges());
        }

        val restored = new Ledger();
        try (val store = new SqliteStore(dir.resolve("a.db"), RETENTION)) {
            store.load(restored);
        }

        assertThat(restored.getCount(chest, DIAMOND)).isEqualTo(15);
        assertThat(restored.getCount(bob, DIAMOND)).isEqualTo(5);
        assertThat(restored.pool(bob, DIAMOND).getOrigin().share(alice)).isCloseTo(0.5, offset(1e-5));
        assertThat(restored.pool(bob, DIAMOND).getCustody().share(bob.asPlayerOrigin()))
                .isCloseTo(1.0, offset(1e-5));
        assertThat(restored.drainChanges().isEmpty()).isTrue();
    }

    @Test
    void emptiedHolderIsDeleted() throws Exception {
        val ledger = new Ledger();
        ledger.mint(CAUSE, chest, DIAMOND, 3, alice);

        try (val store = new SqliteStore(dir.resolve("b.db"), RETENTION)) {
            store.write(ledger.drainChanges());
            ledger.clear(CAUSE, chest);
            store.write(ledger.drainChanges());

            val restored = new Ledger();
            store.load(restored);
            assertThat(restored.getHolders()).isEmpty();
        }
    }

    @Test
    void drainOnlyReportsTouchedHolders() {
        val ledger = new Ledger();
        ledger.mint(CAUSE, chest, DIAMOND, 3, alice);
        ledger.drainChanges();

        ledger.mint(CAUSE, bob, DIAMOND, 1, alice);

        assertThat(ledger.drainChanges().holders()).containsOnlyKeys(bob);
        assertThat(ledger.drainChanges().isEmpty()).isTrue();
    }

    @Test
    void failedBatchIsRetriedBeforeTheNextOne() throws Exception {
        val ledger = new Ledger();
        val written = new ArrayList<Long>();
        val calls = new AtomicInteger();
        ChangeSink flaky = new ChangeSink() {
            @Override
            public void write(LedgerChanges changes) {
                if (calls.incrementAndGet() == 1) {
                    throw new IllegalStateException("disk full");
                }
                written.add(changes.holders().get(chest).get(0).count());
            }

            @Override
            public List<Envelope> history(HolderId holder, ItemKey item, int limit) {
                return List.of();
            }

            @Override
            public void prune(long now) {}

            @Override
            public void close() {}
        };
        val failures = new AtomicInteger();

        try (val writer = new WriteBehind(flaky, e -> failures.incrementAndGet(), Duration.ofSeconds(30))) {
            ledger.mint(CAUSE, chest, DIAMOND, 3, alice);
            writer.submit(ledger.drainChanges());
            ledger.mint(CAUSE, chest, DIAMOND, 2, alice);
            writer.submit(ledger.drainChanges());
        }

        assertThat(failures.get()).isEqualTo(1);
        assertThat(written).containsExactly(3L, 5L);
    }

    @Test
    void restartReplaysJournalTailNotCoveredBySnapshot() throws Exception {
        val ledger = new Ledger();
        ledger.mint(Cause.MINE, bob, DIAMOND, 10, bob.asPlayerOrigin());
        ledger.transfer(CAUSE, bob, chest, DIAMOND, 10);
        val head = ledger.drainChanges();
        ledger.transfer(CAUSE, chest, bob, DIAMOND, 4);
        val tail = ledger.drainChanges();

        try (val store = new SqliteStore(dir.resolve("c.db"), RETENTION)) {
            store.write(head);
            // Simulates a crash between journal and snapshot: only the events of the tail reach the journal.
            store.write(new LedgerChanges(Map.of(), List.of(), tail.events(), head.lastSeq()));
        }

        val restored = new Ledger();
        try (val store = new SqliteStore(dir.resolve("c.db"), RETENTION)) {
            store.load(restored);
        }

        assertThat(restored.getCount(chest, DIAMOND)).isEqualTo(6);
        assertThat(restored.getCount(bob, DIAMOND)).isEqualTo(4);
        assertThat(restored.getFlows().flow(bob.asPlayerOrigin(), bob.asPlayerOrigin()))
                .isZero();
        // The tail made its holders dirty, and the next event continues the sequence.
        assertThat(restored.drainChanges().holders()).containsOnlyKeys(chest, bob);
        restored.burn(CAUSE, bob, DIAMOND, 1);
        assertThat(restored.drainChanges().events()).extracting(Envelope::seq).containsExactly(tail.lastSeq() + 1);
    }

    @Test
    void historyIsNewestFirstAndFiltersByHolderAndItem() throws Exception {
        val stick = new ItemKey("minecraft:stick");
        val pickaxe = new ItemKey("minecraft:diamond_pickaxe");
        val ledger = new Ledger();
        ledger.mint(Cause.MINE, bob, DIAMOND, 5, bob.asPlayerOrigin());
        ledger.mint(Cause.LOOT, bob, stick, 2, OriginId.LOOT);
        ledger.convert(Cause.CRAFT, bob, Map.of(DIAMOND, 3L, stick, 2L), Map.of(pickaxe, 1L));
        ledger.transfer(CAUSE, bob, chest, DIAMOND, 2);

        try (val store = new SqliteStore(dir.resolve("d.db"), RETENTION)) {
            store.write(ledger.drainChanges());

            assertThat(store.history(bob, null, 10)).extracting(Envelope::seq).containsExactly(4L, 3L, 2L, 1L);
            assertThat(store.history(chest, null, 10)).extracting(Envelope::seq).containsExactly(4L);
            assertThat(store.history(bob, null, 2)).extracting(Envelope::seq).containsExactly(4L, 3L);
            // The conversion matches by item inside it, and "diamond" must not match "diamond_pickaxe".
            assertThat(store.history(bob, stick, 10)).extracting(Envelope::seq).containsExactly(3L, 2L);
            assertThat(store.history(bob, pickaxe, 10))
                    .extracting(Envelope::seq)
                    .containsExactly(3L);

            val converted = store.history(bob, pickaxe, 1).get(0);
            assertThat(converted.cause()).isEqualTo(Cause.CRAFT);
            assertThat(converted.event())
                    .isEqualTo(new LedgerEvent.Converted(bob, Map.of(DIAMOND, 3L, stick, 2L), Map.of(pickaxe, 1L)));
            assertThat(store.history(chest, null, 1).get(0).event())
                    .isEqualTo(new LedgerEvent.Transferred(bob, chest, DIAMOND, 2, TaintVector.EMPTY));
        }
    }

    @Test
    void pruneDropsAutomationEarlierThanEventsInvolvingPlayers() throws Exception {
        val hopperTarget = HolderId.block("world", 5, 64, 5);
        val day = TimeUnit.DAYS.toMillis(1);
        val now = 100 * day;
        val clock = new AtomicLong(now - 10 * day);
        val ledger = new Ledger(clock::get);

        ledger.mint(Cause.MINE, bob, DIAMOND, 5, bob.asPlayerOrigin()); // durable, 10 days old
        ledger.mint(Cause.MINE, chest, DIAMOND, 5, OriginId.UNKNOWN); // minted: durable
        ledger.transfer(Cause.HOPPER, chest, hopperTarget, DIAMOND, 1); // automation, 10 days old

        try (val store = new SqliteStore(dir.resolve("e.db"), RETENTION)) {
            store.write(ledger.drainChanges());
            // Back-to-back moves merge within a batch, so the newer one needs its own.
            clock.set(now - 2 * day);
            ledger.transfer(Cause.HOPPER, chest, hopperTarget, DIAMOND, 1); // automation, 2 days old
            store.write(ledger.drainChanges());
            store.prune(now);

            assertThat(store.history(chest, null, 10)).extracting(Envelope::seq).containsExactly(4L, 2L);
            assertThat(store.history(bob, null, 10)).extracting(Envelope::seq).containsExactly(1L);
        }
    }

    @Test
    void databaseOfAnotherSchemaVersionIsDropped() throws Exception {
        val url = "jdbc:sqlite:" + dir.resolve("f.db");
        try (val connection = DriverManager.getConnection(url);
                val statement = connection.createStatement()) {
            statement.execute("CREATE TABLE pool (legacy TEXT)");
            statement.execute("PRAGMA user_version=1");
        }

        val ledger = new Ledger();
        ledger.mint(CAUSE, chest, DIAMOND, 1, alice);
        try (val store = new SqliteStore(url, RETENTION)) {
            store.write(ledger.drainChanges());
        }
    }
}

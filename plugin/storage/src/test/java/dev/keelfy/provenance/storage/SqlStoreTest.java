package dev.keelfy.provenance.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.core.LedgerChanges;
import dev.keelfy.provenance.core.event.Envelope;
import dev.keelfy.provenance.core.event.LedgerEvent;
import dev.keelfy.provenance.core.model.Cause;
import dev.keelfy.provenance.core.model.Chunk;
import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.ItemKey;
import dev.keelfy.provenance.core.model.OriginId;
import dev.keelfy.provenance.core.taint.TaintVector;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
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

class SqlStoreTest {

    private static final Cause CAUSE = Cause.CONTAINER;
    private static final Retention RETENTION = new Retention(Duration.ofDays(30), Duration.ofDays(3));
    private static final Duration BUCKET = Duration.ofHours(1);
    private static final ItemKey DIAMOND = new ItemKey("minecraft:diamond");

    @TempDir
    Path dir;

    private final OriginId alice = OriginId.player(UUID.randomUUID());
    private final Holder bob = Holder.player(UUID.randomUUID());
    private final Holder chest = Holder.block("world", 1, 64, 2);

    @Test
    void roundTripKeepsPoolsVectorsAndFlows() throws Exception {
        val ledger = new Ledger();
        ledger.mint(CAUSE, chest, DIAMOND, 10, alice);
        ledger.mint(CAUSE, chest, DIAMOND, 10, OriginId.MOB);
        ledger.transfer(CAUSE, chest, bob, DIAMOND, 5);

        try (val store = store("a.db")) {
            store.write(ledger.drainChanges());
        }

        val restored = new Ledger();
        try (val store = store("a.db")) {
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

        try (val store = store("b.db")) {
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
            public List<Envelope> history(Holder holder, ItemKey item, int limit) {
                return List.of();
            }

            @Override
            public Trace trace(Holder root, ItemKey item, long since, int depth, int limit) {
                return new Trace(List.of(), false);
            }

            @Override
            public void prune(long now) {}

            @Override
            public void compact(long now) {}

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

        try (val store = store("c.db")) {
            store.write(head);
            // Simulates a crash between journal and snapshot: only the events of the tail reach the journal.
            store.write(new LedgerChanges(Map.of(), List.of(), tail.events(), head.lastSeq()));
        }

        val restored = new Ledger();
        try (val store = store("c.db")) {
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

        try (val store = store("d.db")) {
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
    void traceFollowsHoldersUpToDepthLimitAndItem() throws Exception {
        val carol = Holder.player(UUID.randomUUID());
        val dave = Holder.player(UUID.randomUUID());
        val stick = new ItemKey("minecraft:stick");
        val clock = new AtomicLong(1000);
        val ledger = new Ledger(clock::get);
        ledger.mint(Cause.MINE, bob, DIAMOND, 10, bob.asPlayerOrigin()); // 1
        ledger.mint(Cause.MINE, dave, DIAMOND, 1, dave.asPlayerOrigin()); // 2: unrelated to bob
        ledger.transfer(CAUSE, bob, chest, DIAMOND, 6); // 3
        ledger.transfer(CAUSE, chest, carol, DIAMOND, 4); // 4
        ledger.transfer(CAUSE, carol, dave, DIAMOND, 1); // 5
        ledger.mint(Cause.LOOT, chest, stick, 2, OriginId.LOOT); // 6

        try (val store = store("g.db")) {
            store.write(ledger.drainChanges());

            // Hop 1: bob's own events; hop 2 adds the chest's; carol and dave stay out of reach.
            assertThat(store.trace(bob, null, 0, 1, 100).events())
                    .extracting(Envelope::seq)
                    .containsExactly(1L, 3L);
            assertThat(store.trace(bob, null, 0, 2, 100).events())
                    .extracting(Envelope::seq)
                    .containsExactly(1L, 3L, 4L, 6L);
            assertThat(store.trace(bob, null, 0, 3, 100).events())
                    .extracting(Envelope::seq)
                    .containsExactly(1L, 3L, 4L, 5L, 6L);
            assertThat(store.trace(bob, null, 0, 9, 100).events())
                    .extracting(Envelope::seq)
                    .containsExactly(1L, 2L, 3L, 4L, 5L, 6L);

            // The item filter applies to every hop.
            assertThat(store.trace(bob, DIAMOND, 0, 9, 100).events())
                    .extracting(Envelope::seq)
                    .containsExactly(1L, 2L, 3L, 4L, 5L);
            assertThat(store.trace(chest, stick, 0, 9, 100).events())
                    .extracting(Envelope::seq)
                    .containsExactly(6L);

            // Past the limit the newest events are kept and the cut is reported.
            val cut = store.trace(bob, null, 0, 9, 4);
            assertThat(cut.events()).extracting(Envelope::seq).containsExactly(3L, 4L, 5L, 6L);
            assertThat(cut.truncated()).isTrue();
            assertThat(store.trace(bob, null, 0, 9, 6).truncated()).isFalse();

            // Nothing is returned from before the window.
            assertThat(store.trace(bob, null, 1001, 9, 100).events()).isEmpty();
        }
    }

    @Test
    void traceWalksThroughDroppedItemsWithoutSpendingDepth() throws Exception {
        val first = Holder.entity(UUID.randomUUID()).described("minecraft:item");
        val second = Holder.entity(UUID.randomUUID()).described("minecraft:item");
        val ledger = new Ledger();
        ledger.mint(Cause.MINE, first, DIAMOND, 1, bob.asPlayerOrigin()); // 1
        ledger.transfer(Cause.DROP, first, second, DIAMOND, 1); // 2: the stacks merge
        ledger.transfer(Cause.PICKUP, second, bob, DIAMOND, 1); // 3

        try (val store = store("m.db")) {
            store.write(ledger.drainChanges());

            assertThat(store.trace(bob, null, 0, 1, 100).events())
                    .extracting(Envelope::seq)
                    .containsExactly(1L, 2L, 3L);
        }
    }

    @Test
    void eachEventKeepsTheTypeAndNameHoldersHadAtTheTime() throws Exception {
        val ledger = new Ledger();
        val oldChest = chest.described("minecraft:chest");
        val furnace = chest.described("minecraft:furnace");
        val villager = Holder.entity(UUID.randomUUID()).described("minecraft:villager", "Bob");
        ledger.mint(CAUSE, oldChest, DIAMOND, 10, alice);
        ledger.transfer(CAUSE, oldChest, villager, DIAMOND, 4);
        ledger.mint(CAUSE, furnace, DIAMOND, 1, alice);

        try (val store = store("l.db")) {
            store.write(ledger.drainChanges());
            val events = store.trace(chest, null, 0, 2, 10).events();

            assertThat(events)
                    .extracting(e -> e.event().holder().type())
                    .containsExactly("minecraft:chest", "minecraft:chest", "minecraft:furnace");
            val transfer = (LedgerEvent.Transferred) events.get(1).event();
            assertThat(transfer.to().type()).isEqualTo("minecraft:villager");
            assertThat(transfer.to().displayName()).isEqualTo("Bob");
        }
    }

    @Test
    void pruneDropsAutomationEarlierThanEventsInvolvingPlayers() throws Exception {
        val hopperTarget = Holder.block("world", 5, 64, 5);
        val day = TimeUnit.DAYS.toMillis(1);
        val now = 100 * day;
        val clock = new AtomicLong(now - 10 * day);
        val ledger = new Ledger(clock::get);

        ledger.mint(Cause.MINE, bob, DIAMOND, 5, bob.asPlayerOrigin()); // durable, 10 days old
        ledger.mint(Cause.MINE, chest, DIAMOND, 5, OriginId.UNKNOWN); // minted: durable
        ledger.transfer(Cause.HOPPER, chest, hopperTarget, DIAMOND, 1); // automation, 10 days old

        try (val store = store("e.db")) {
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
    void closedBucketAddsUpSimilarEventsAndFoldsDroppedStacks() throws Exception {
        val hour = BUCKET.toMillis();
        val clock = new AtomicLong(10 * hour);
        val ledger = new Ledger(clock::get);
        val drops = "minecraft:item";
        val cobble = new ItemKey("minecraft:cobblestone");
        for (var i = 0; i < 1000; i++) {
            clock.addAndGet(1000);
            val drop = Holder.entity(UUID.randomUUID()).described(drops);
            ledger.mint(Cause.MINE, drop, cobble, 1, bob.asPlayerOrigin());
            ledger.transfer(Cause.PICKUP, drop, bob, cobble, 1);
        }
        val lying = Holder.entity(UUID.randomUUID()).described(drops);
        ledger.mint(Cause.MINE, lying, cobble, 3, bob.asPlayerOrigin());
        ledger.transfer(CAUSE, bob, chest, cobble, 10);
        ledger.transfer(CAUSE, bob, chest, cobble, 10);

        try (val store = store("k.db")) {
            store.write(ledger.drainChanges());

            // The bucket has not ended yet.
            store.compact(11 * hour);
            assertThat(store.history(bob, null, 5000)).hasSize(1002);

            clock.set(13 * hour);
            ledger.mint(Cause.MINE, bob, cobble, 1, bob.asPlayerOrigin());
            ledger.mint(Cause.MINE, bob, cobble, 1, bob.asPlayerOrigin());
            store.write(ledger.drainChanges());
            store.compact(12 * hour + hour / 2);
            store.compact(12 * hour + hour / 2);

            val events = store.history(bob, null, 5000);
            assertThat(events).hasSize(4);
            assertThat(events.get(2).event())
                    .isEqualTo(new LedgerEvent.Transferred(bob, chest, cobble, 20, TaintVector.EMPTY));
            assertThat(events.get(3).event())
                    .isEqualTo(new LedgerEvent.Minted(bob, cobble, 1000, bob.asPlayerOrigin()));
            assertThat(events.get(3).seq()).isEqualTo(1);
            assertThat(events.get(3).time()).isEqualTo(10 * hour + 1000);
            assertThat(events.get(3).cause()).isEqualTo(Cause.MINE);

            // The stack nobody picked up stays on the ground.
            assertThat(store.history(lying, null, 10))
                    .extracting(e -> e.event())
                    .hasSize(1);
        }

        val restored = new Ledger();
        try (val store = store("k.db")) {
            store.load(restored);
        }
        assertThat(restored.getCount(bob, cobble)).isEqualTo(982);
        assertThat(restored.getCount(chest, cobble)).isEqualTo(20);
    }

    @Test
    void closedBucketKeepsChunksApartAndCollapsesKilledMobsPerChunk() throws Exception {
        val hour = BUCKET.toMillis();
        val clock = new AtomicLong(10 * hour);
        val ledger = new Ledger(clock::get);
        val flesh = new ItemKey("minecraft:rotten_flesh");
        val farm = new Chunk("world", 1, 1);
        val field = new Chunk("world", -5, 7);
        for (var i = 0; i < 800; i++) {
            clock.addAndGet(1000);
            val chunk = i < 500 ? farm : field;
            val zombie = Holder.entity(UUID.randomUUID()).described("minecraft:zombie", null, chunk);
            val drop = Holder.entity(UUID.randomUUID()).described("minecraft:item", null, chunk);
            ledger.mint(Cause.KILL, zombie, flesh, 1, bob.asPlayerOrigin());
            ledger.transfer(Cause.KILL, zombie, drop, flesh, 1);
            ledger.transfer(Cause.PICKUP, drop, bob, flesh, 1);
        }

        // Another player farming the same chunk must not end up in bob's history.
        val carol = Holder.player(UUID.randomUUID());
        for (var i = 0; i < 100; i++) {
            clock.addAndGet(1000);
            val zombie = Holder.entity(UUID.randomUUID()).described("minecraft:zombie", null, farm);
            val drop = Holder.entity(UUID.randomUUID()).described("minecraft:item", null, farm);
            ledger.mint(Cause.KILL, zombie, flesh, 1, carol.asPlayerOrigin());
            ledger.transfer(Cause.KILL, zombie, drop, flesh, 1);
            ledger.transfer(Cause.PICKUP, drop, carol, flesh, 1);
        }

        try (val store = store("z.db")) {
            store.write(ledger.drainChanges());
            store.compact(12 * hour);

            val trace = store.trace(bob, null, 0, 2, 100).events();
            assertThat(trace).hasSize(4);
            val byChunk = trace.stream()
                    .filter(e -> e.event() instanceof LedgerEvent.Transferred)
                    .toList();
            assertThat(byChunk).extracting(Envelope::chunk).containsExactlyInAnyOrder(farm, field);
            for (val e : byChunk) {
                val transfer = (LedgerEvent.Transferred) e.event();
                assertThat(transfer.holder().id().id()).startsWith("mob:minecraft:zombie@world:");
                assertThat(transfer.amount()).isEqualTo(e.chunk().equals(farm) ? 500 : 300);
                assertThat(e.until()).isGreaterThan(e.time());
            }
            assertThat(trace.stream()
                            .filter(e -> e.event() instanceof LedgerEvent.Minted)
                            .mapToLong(e -> ((LedgerEvent.Minted) e.event()).amount())
                            .sum())
                    .isEqualTo(800);
        }
    }

    @Test
    void databaseOfAnotherSchemaVersionIsDropped() throws Exception {
        try (val connection = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("f.db"));
                val statement = connection.createStatement()) {
            statement.execute("CREATE TABLE pool (legacy TEXT)");
            statement.execute("PRAGMA user_version=1");
        }

        val ledger = new Ledger();
        ledger.mint(CAUSE, chest, DIAMOND, 1, alice);
        try (val store = store("f.db")) {
            store.write(ledger.drainChanges());

            val restored = new Ledger();
            store.load(restored);
            assertThat(restored.getCount(chest, DIAMOND)).isEqualTo(1);
        }
    }

    @Test
    void serversSharingADatabaseLoadOnlyTheirOwn() throws Exception {
        val farm = new Ledger();
        farm.mint(CAUSE, chest, DIAMOND, 7, alice);
        val survival = new Ledger();
        survival.mint(CAUSE, chest, DIAMOND, 2, OriginId.MOB);

        try (val farmStore = store("farm", "shared.db");
                val survivalStore = store("survival", "shared.db")) {
            farmStore.write(farm.drainChanges());
            survivalStore.write(survival.drainChanges());

            val farmRestored = new Ledger();
            farmStore.load(farmRestored);
            val survivalRestored = new Ledger();
            survivalStore.load(survivalRestored);

            assertThat(farmRestored.getCount(chest, DIAMOND)).isEqualTo(7);
            assertThat(survivalRestored.getCount(chest, DIAMOND)).isEqualTo(2);
            assertThat(farmRestored.drainChanges().lastSeq()).isEqualTo(1);
            assertThat(survivalRestored.drainChanges().lastSeq()).isEqualTo(1);
        }
    }

    @Test
    void playerHistoryReadsAcrossServersButBlockHistoryDoesNot() throws Exception {
        val farm = new Ledger(() -> 1_000);
        farm.mint(CAUSE, chest, DIAMOND, 7, alice);
        farm.transfer(CAUSE, chest, bob, DIAMOND, 7);
        val survival = new Ledger(() -> 2_000);
        survival.sync(bob, Holder.server("farm"), farm.snapshot(bob));

        try (val farmStore = store("farm", "shared.db");
                val survivalStore = store("survival", "shared.db")) {
            farmStore.write(farm.drainChanges());
            survivalStore.write(survival.drainChanges());

            val seen = survivalStore.history(bob, null, 10);
            assertThat(seen).extracting(Envelope::server).containsExactly(null, "farm");
            assertThat(seen.get(0).event()).isInstanceOf(LedgerEvent.Synced.class);
            assertThat(survivalStore.history(chest, null, 10)).isEmpty();
            assertThat(farmStore.history(chest, null, 10))
                    .extracting(Envelope::server)
                    .containsOnlyNulls();
        }
    }

    @Test
    void syncedInventoryKeepsItsOriginAcrossReload() throws Exception {
        val source = new Ledger();
        source.mint(CAUSE, bob, DIAMOND, 4, alice);
        val ledger = new Ledger();
        ledger.sync(bob, Holder.server("farm"), source.snapshot(bob));

        try (val store = store("survival", "sync.db")) {
            store.write(ledger.drainChanges());
            // the snapshot lags behind: the journal tail alone must rebuild the synced pools
            val replayed = new Ledger();
            store.load(replayed);

            assertThat(replayed.getCount(bob, DIAMOND)).isEqualTo(4);
            assertThat(replayed.pool(bob, DIAMOND).getOrigin().share(alice)).isCloseTo(1.0, offset(1e-5));
            assertThat(store.history(bob, DIAMOND, 10)).hasSize(1);
        }
    }

    private SqlStore store(String file) throws SQLException {
        return store("main", file);
    }

    private SqlStore store(String server, String file) throws SQLException {
        return new SqlStore(server, Database.sqlite(file), dir, RETENTION, BUCKET);
    }
}

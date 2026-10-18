package dev.keelfy.provenance.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.keelfy.provenance.core.HolderId;
import dev.keelfy.provenance.core.ItemKey;
import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.core.LedgerChanges;
import dev.keelfy.provenance.core.OriginId;
import lombok.val;

class SqliteStoreTest {

    private static final ItemKey DIAMOND = new ItemKey("minecraft:diamond");

    @TempDir
    Path dir;

    private final OriginId alice = OriginId.player(UUID.randomUUID());
    private final HolderId bob = HolderId.player(UUID.randomUUID());
    private final HolderId chest = HolderId.block("world", 1, 64, 2);

    @Test
    void roundTripKeepsPoolsVectorsAndFlows() throws Exception {
        val ledger = new Ledger();
        ledger.mint(chest, DIAMOND, 10, alice);
        ledger.mint(chest, DIAMOND, 10, OriginId.MOB);
        ledger.transfer(chest, bob, DIAMOND, 5);

        try (val store = new SqliteStore(dir.resolve("a.db"))) {
            store.write(ledger.drainChanges());
        }

        val restored = new Ledger();
        try (val store = new SqliteStore(dir.resolve("a.db"))) {
            store.load(restored);
        }

        assertThat(restored.getCount(chest, DIAMOND)).isEqualTo(15);
        assertThat(restored.getCount(bob, DIAMOND)).isEqualTo(5);
        assertThat(restored.pool(bob, DIAMOND).getOrigin().share(alice)).isCloseTo(0.5, offset(1e-5));
        assertThat(restored.pool(bob, DIAMOND).getCustody().share(bob.asPlayerOrigin())).isCloseTo(1.0, offset(1e-5));
        assertThat(restored.drainChanges().isEmpty()).isTrue();
    }

    @Test
    void emptiedHolderIsDeleted() throws Exception {
        val ledger = new Ledger();
        ledger.mint(chest, DIAMOND, 3, alice);

        try (val store = new SqliteStore(dir.resolve("b.db"))) {
            store.write(ledger.drainChanges());
            ledger.clear(chest);
            store.write(ledger.drainChanges());

            val restored = new Ledger();
            store.load(restored);
            assertThat(restored.getHolders()).isEmpty();
        }
    }

    @Test
    void drainOnlyReportsTouchedHolders() {
        val ledger = new Ledger();
        ledger.mint(chest, DIAMOND, 3, alice);
        ledger.drainChanges();

        ledger.mint(bob, DIAMOND, 1, alice);

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
            public void close() {
            }
        };
        val failures = new AtomicInteger();

        try (val writer = new WriteBehind(flaky, e -> failures.incrementAndGet())) {
            ledger.mint(chest, DIAMOND, 3, alice);
            writer.submit(ledger.drainChanges());
            ledger.mint(chest, DIAMOND, 2, alice);
            writer.submit(ledger.drainChanges());
        }

        assertThat(failures.get()).isEqualTo(1);
        assertThat(written).containsExactly(3L, 5L);
    }
}

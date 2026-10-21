package dev.keelfy.provenance.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import dev.keelfy.provenance.core.event.Envelope;
import dev.keelfy.provenance.core.event.LedgerEvent;
import dev.keelfy.provenance.core.model.Cause;
import dev.keelfy.provenance.core.model.Chunk;
import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.ItemKey;
import dev.keelfy.provenance.core.model.Kind;
import dev.keelfy.provenance.core.model.OriginId;
import dev.keelfy.provenance.core.taint.TaintVector;
import dev.keelfy.provenance.storage.Trace;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.val;
import org.junit.jupiter.api.Test;

class ReportCompactionTest {

    private static final ItemKey DIAMOND = new ItemKey("minecraft:diamond");
    private static final ItemKey COAL = new ItemKey("minecraft:coal");
    private static final long HOUR = Duration.ofHours(1).toMillis();
    private static final long NOW = 100 * 24 * HOUR;

    private final UUID aliceId = UUID.randomUUID();
    private final Holder alice = Holder.player(aliceId);
    private final Holder chest = Holder.block("world", 1, 64, 2);

    @Test
    void historyThatFitsStaysAsStored() {
        val events = List.of(give(1, NOW - 50 * HOUR, 1), give(2, NOW - 49 * HOUR, 1));

        val fit = ReportCompaction.fit(new Trace(events, false), NOW, 10);

        assertThat(fit.events()).isEqualTo(events);
        assertThat(fit.truncated()).isFalse();
    }

    @Test
    void olderEventsAddUpBeforeRecentOnes() {
        val events = new ArrayList<Envelope>();
        // Two days ago, an hour apart: one 6 hour bucket
        for (var i = 0; i < 4; i++) {
            events.add(give(i + 1, NOW - 48 * HOUR + i * HOUR, 2));
        }
        events.add(give(10, NOW - HOUR, 1));
        events.add(give(11, NOW - HOUR / 2, 1));

        val fit = ReportCompaction.fit(new Trace(events, false), NOW, 3);

        assertThat(fit.truncated()).isFalse();
        assertThat(fit.events()).hasSize(3);
        val old = fit.events().get(0);
        assertThat(old.seq()).isEqualTo(1);
        assertThat(old.time()).isEqualTo(NOW - 48 * HOUR);
        assertThat(old.until()).isEqualTo(NOW - 45 * HOUR);
        assertThat(((LedgerEvent.Transferred) old.event()).amount()).isEqualTo(8);
        // The last day is untouched
        assertThat(fit.events().subList(1, 3)).isEqualTo(events.subList(4, 6));
    }

    @Test
    void mergedTransfersWeighCustodyByAmount() {
        val bob = OriginId.player(UUID.randomUUID());
        val events = List.of(
                envelope(1, NOW - 48 * HOUR, transfer(1, TaintVector.of(OriginId.player(aliceId)))),
                envelope(2, NOW - 47 * HOUR, transfer(3, TaintVector.of(bob))));

        val fit = ReportCompaction.fit(new Trace(events, false), NOW, 1);

        val custody = ((LedgerEvent.Transferred) fit.events().get(0).event()).custody();
        assertThat(custody.share(bob)).isCloseTo(0.75, offset(1e-6));
    }

    @Test
    void differentEventsStayApart() {
        val events = List.of(
                give(1, NOW - 48 * HOUR, 1),
                envelope(2, NOW - 48 * HOUR, new LedgerEvent.Transferred(chest, alice, COAL, 1, TaintVector.EMPTY)),
                envelope(3, NOW - 48 * HOUR, new LedgerEvent.Cleared(chest)));

        val fit = ReportCompaction.fit(new Trace(events, false), NOW, 3);

        assertThat(fit.events()).isEqualTo(events);
    }

    @Test
    void creaturesOfOneKillerMergeAcrossChunks() {
        val events = new ArrayList<Envelope>();
        for (var x = 0; x < 3; x++) {
            val mob = new Holder(Kind.ENTITY, "mob:minecraft:zombie@world:" + x + ":0#p:" + aliceId)
                    .described("minecraft:zombie");
            events.add(new Envelope(
                    x + 1,
                    NOW - 48 * HOUR,
                    NOW - 48 * HOUR,
                    Cause.KILL,
                    new LedgerEvent.Transferred(
                            mob, alice, new ItemKey("minecraft:rotten_flesh"), 2, TaintVector.EMPTY),
                    new Chunk("world", x, 0)));
        }

        val fit = ReportCompaction.fit(new Trace(events, false), NOW, 1);

        assertThat(fit.events()).hasSize(1);
        val merged = fit.events().get(0);
        assertThat(merged.event().holder().id().key()).isEqualTo("ENTITY/mob:minecraft:zombie#p:" + aliceId);
        assertThat(merged.event().holder().type()).isEqualTo("minecraft:zombie");
        assertThat(merged.chunk()).isNull();
        assertThat(((LedgerEvent.Transferred) merged.event()).amount()).isEqualTo(6);
    }

    @Test
    void droppedStacksBecomeTheGround() {
        val flesh = new ItemKey("minecraft:rotten_flesh");
        val mob = new Holder(Kind.ENTITY, "mob:minecraft:zombie@world:0:0#env:mob").described("minecraft:zombie");
        val events = new ArrayList<Envelope>();
        for (var i = 0; i < 3; i++) {
            val stack = Holder.entity(UUID.randomUUID()).described("minecraft:item");
            val time = NOW - 48 * HOUR + i;
            events.add(new Envelope(
                    3 * i + 1, time, Cause.KILL, new LedgerEvent.Transferred(mob, stack, flesh, 1, TaintVector.EMPTY)));
            events.add(new Envelope(
                    3 * i + 2,
                    time,
                    Cause.DROP,
                    new LedgerEvent.Transferred(
                            stack,
                            Holder.entity(UUID.randomUUID()).described("minecraft:item"),
                            flesh,
                            1,
                            TaintVector.EMPTY)));
            events.add(new Envelope(3 * i + 3, time, Cause.DESPAWN, new LedgerEvent.Cleared(stack)));
        }

        val fit = ReportCompaction.fit(new Trace(events, false), NOW, 2);

        // The farm's loot fell on the ground and despawned; stacks merging on the ground moved nothing
        assertThat(fit.events()).hasSize(2);
        val dropped = (LedgerEvent.Transferred) fit.events().get(0).event();
        assertThat(dropped.to().id().key()).isEqualTo("ENTITY/" + ReportCompaction.GROUND);
        assertThat(dropped.to().type()).isEqualTo("minecraft:item");
        assertThat(dropped.amount()).isEqualTo(3);
        assertThat(fit.events().get(1).event()).isEqualTo(new LedgerEvent.Cleared(dropped.to()));
    }

    @Test
    void conversionsSumTheirStacks() {
        val events = List.of(
                envelope(1, NOW - 48 * HOUR, new LedgerEvent.Converted(alice, Map.of(COAL, 1L), Map.of(DIAMOND, 1L))),
                envelope(2, NOW - 47 * HOUR, new LedgerEvent.Converted(alice, Map.of(COAL, 2L), Map.of(DIAMOND, 2L))));

        val fit = ReportCompaction.fit(new Trace(events, false), NOW, 1);

        val converted = (LedgerEvent.Converted) fit.events().get(0).event();
        assertThat(converted.inputs()).containsExactly(Map.entry(COAL, 3L));
        assertThat(converted.outputs()).containsExactly(Map.entry(DIAMOND, 3L));
    }

    @Test
    void whatDoesNotFitLosesTheOldest() {
        val events = List.of(
                give(1, NOW - 3 * HOUR, 1),
                envelope(2, NOW - 2 * HOUR, new LedgerEvent.Cleared(chest)),
                give(3, NOW - HOUR, 1));

        val fit = ReportCompaction.fit(new Trace(events, false), NOW, 1);

        assertThat(fit.truncated()).isTrue();
        assertThat(fit.events()).hasSize(1);
        // Both gifts add up into one that starts before the clear
        assertThat(fit.events().get(0).event()).isInstanceOf(LedgerEvent.Cleared.class);
    }

    private Envelope give(long seq, long time, long amount) {
        return envelope(seq, time, transfer(amount, TaintVector.EMPTY));
    }

    private LedgerEvent.Transferred transfer(long amount, TaintVector custody) {
        return new LedgerEvent.Transferred(chest, alice, DIAMOND, amount, custody);
    }

    private static Envelope envelope(long seq, long time, LedgerEvent event) {
        return new Envelope(seq, time, Cause.CONTAINER, event);
    }
}

package dev.keelfy.provenance.app.web;

import dev.keelfy.provenance.core.event.Envelope;
import dev.keelfy.provenance.core.event.LedgerEvent;
import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.ItemKey;
import dev.keelfy.provenance.core.model.Kind;
import dev.keelfy.provenance.core.taint.TaintVector;
import dev.keelfy.provenance.storage.Trace;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import lombok.experimental.UtilityClass;
import lombok.val;
import org.jspecify.annotations.Nullable;

/**
 * Fits a long history into a snapshot a phone can open. The journal keeps an hour per chunk at best; a report of a week
 * would still be tens of thousands of events. Events that differ in nothing but time, place and amount add up into one
 * that lasts from the first to the last, in buckets that grow with age: the last day stays as stored as long as it
 * fits, older days become a line per day and holder. The finest {@link #LEVELS level} that fits is taken; if even the
 * coarsest does not, the oldest events are cut off.
 *
 * <p>Merged events lose their chunk unless they all share one, and creatures one player killed are no longer told apart
 * by chunk: a farm is one holder instead of one per chunk. Dropped stacks all become one, {@link #GROUND}: a spawner
 * farm drops hundreds of stacks an hour, each its own holder in the journal.
 *
 * @author keelfy
 */
@UtilityClass
public class ReportCompaction {

    /** How far back the history counts as recent. */
    static final long RECENT = Duration.ofDays(1).toMillis();

    /** Every dropped stack, once summed up; the viewer folds it like any other item entity. */
    static final String GROUND = "ground";

    private static final String ITEM_ENTITY = "minecraft:item";
    private static final long HOUR = Duration.ofHours(1).toMillis();
    private static final long DAY = Duration.ofDays(1).toMillis();

    /** Bucket widths for recent and for older events, finest first; 0 keeps events as stored. */
    private record Level(long recent, long older) {}

    private static final List<Level> LEVELS = List.of(
            new Level(0, 0),
            new Level(0, 6 * HOUR),
            new Level(HOUR, DAY),
            new Level(6 * HOUR, 7 * DAY),
            new Level(DAY, Long.MAX_VALUE));

    /** What an event is, apart from when and where it happened and how much moved. */
    private record Key(
            long width,
            long bucket,
            String cause,
            String type,
            String holder,
            @Nullable String other,
            String what) {}

    /** @param trace events oldest first */
    public Trace fit(Trace trace, long now, int maxEvents) {
        var events = trace.events();
        for (val level : LEVELS) {
            events = compact(trace.events(), now, level);
            if (events.size() <= maxEvents) {
                return new Trace(events, trace.truncated());
            }
        }
        return new Trace(List.copyOf(events.subList(events.size() - maxEvents, events.size())), true);
    }

    private List<Envelope> compact(List<Envelope> events, long now, Level level) {
        if (level.recent() == 0 && level.older() == 0) {
            return events;
        }

        val merged = new LinkedHashMap<Key, Envelope>();
        val kept = new ArrayList<Envelope>();
        for (val envelope : events) {
            val width = now - envelope.time() < RECENT ? level.recent() : level.older();
            if (width == 0) {
                kept.add(envelope);
                continue;
            }
            val summed = summed(envelope);
            if (summed.event() instanceof LedgerEvent.Transferred e
                    && e.holder().equals(e.to())) {
                continue; // from one stack on the ground to another: nothing moved
            }
            val key = key(summed, width);
            if (key == null) {
                kept.add(summed);
            } else {
                merged.merge(key, summed, ReportCompaction::merge);
            }
        }
        // By seq: a merged event keeps its first one's, so the order stays the journal's
        val bySeq = new TreeMap<Long, Envelope>();
        kept.forEach(envelope -> bySeq.put(envelope.seq(), envelope));
        merged.values().forEach(envelope -> bySeq.put(envelope.seq(), envelope));
        return List.copyOf(bySeq.values());
    }

    /** {@code null} for events that never merge. */
    private @Nullable Key key(Envelope envelope, long width) {
        val bucket = envelope.time() / width;
        val cause = envelope.cause().key();
        return switch (envelope.event()) {
            case LedgerEvent.Minted e ->
                new Key(
                        width,
                        bucket,
                        cause,
                        "M",
                        id(e.holder()),
                        null,
                        e.item().key() + "|" + e.origin().key());
            case LedgerEvent.Transferred e ->
                new Key(
                        width,
                        bucket,
                        cause,
                        "T",
                        id(e.holder()),
                        id(e.to()),
                        e.item().key());
            case LedgerEvent.Burned e ->
                new Key(
                        width,
                        bucket,
                        cause,
                        "B",
                        id(e.holder()),
                        null,
                        e.item().key());
            case LedgerEvent.Converted e ->
                new Key(
                        width,
                        bucket,
                        cause,
                        "C",
                        id(e.holder()),
                        null,
                        e.inputs().keySet() + ">" + e.outputs().keySet());
            case LedgerEvent.Cleared e -> new Key(width, bucket, cause, "X", id(e.holder()), null, "");
            case LedgerEvent.Synced e -> null;
        };
    }

    private Envelope merge(Envelope first, Envelope next) {
        val event = switch (first.event()) {
            case LedgerEvent.Minted e ->
                new LedgerEvent.Minted(
                        e.holder(), e.item(), e.amount() + ((LedgerEvent.Minted) next.event()).amount(), e.origin());
            case LedgerEvent.Transferred e -> {
                val other = (LedgerEvent.Transferred) next.event();
                yield new LedgerEvent.Transferred(
                        e.holder(),
                        e.to(),
                        e.item(),
                        e.amount() + other.amount(),
                        TaintVector.mix(e.custody(), e.amount(), other.custody(), other.amount()));
            }
            case LedgerEvent.Burned e ->
                new LedgerEvent.Burned(e.holder(), e.item(), e.amount() + ((LedgerEvent.Burned) next.event()).amount());
            case LedgerEvent.Converted e -> {
                val other = (LedgerEvent.Converted) next.event();
                yield new LedgerEvent.Converted(
                        e.holder(), sum(e.inputs(), other.inputs()), sum(e.outputs(), other.outputs()));
            }
            case LedgerEvent.Cleared e -> e;
            case LedgerEvent.Synced e -> throw new IllegalArgumentException("does not merge: " + e);
        };
        return new Envelope(
                Math.min(first.seq(), next.seq()),
                Math.min(first.time(), next.time()),
                Math.max(first.until(), next.until()),
                first.cause(),
                event,
                Objects.equals(first.chunk(), next.chunk()) ? first.chunk() : null,
                first.server());
    }

    /** Ordered by item key, as {@link LedgerEvent.Converted} wants it. */
    private Map<ItemKey, Long> sum(Map<ItemKey, Long> a, Map<ItemKey, Long> b) {
        val sum = new TreeMap<ItemKey, Long>(Comparator.comparing(ItemKey::key));
        sum.putAll(a);
        b.forEach((key, amount) -> sum.merge(key, amount, Long::sum));
        return sum;
    }

    private String id(Holder holder) {
        return holder.id().key();
    }

    /**
     * The holder an event is summed up under: the ground for a dropped stack; for creatures of one kind one killer
     * killed, the journal's {@code mob:type@chunk#killer} less the chunk.
     */
    private Holder summed(Holder holder) {
        val id = holder.id().id();
        if (holder.id().kind() != Kind.ENTITY) {
            return holder;
        }
        if (ITEM_ENTITY.equals(holder.type())) {
            return new Holder(Kind.ENTITY, GROUND, holder.meta());
        }
        val at = id.indexOf('@');
        if (!id.startsWith("mob:") || at < 0) {
            return holder;
        }
        val hash = id.indexOf('#', at);
        return new Holder(Kind.ENTITY, id.substring(0, at) + (hash < 0 ? "" : id.substring(hash)), holder.meta());
    }

    private Envelope summed(Envelope envelope) {
        val event = switch (envelope.event()) {
            case LedgerEvent.Minted e -> new LedgerEvent.Minted(summed(e.holder()), e.item(), e.amount(), e.origin());
            case LedgerEvent.Transferred e ->
                new LedgerEvent.Transferred(summed(e.holder()), summed(e.to()), e.item(), e.amount(), e.custody());
            case LedgerEvent.Burned e -> new LedgerEvent.Burned(summed(e.holder()), e.item(), e.amount());
            case LedgerEvent.Converted e -> new LedgerEvent.Converted(summed(e.holder()), e.inputs(), e.outputs());
            case LedgerEvent.Cleared e -> new LedgerEvent.Cleared(summed(e.holder()));
            case LedgerEvent.Synced e -> e;
        };
        return new Envelope(
                envelope.seq(),
                envelope.time(),
                envelope.until(),
                envelope.cause(),
                event,
                envelope.chunk(),
                envelope.server());
    }
}

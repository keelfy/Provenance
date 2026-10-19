package dev.keelfy.provenance.storage;

import dev.keelfy.provenance.core.model.Cause;
import dev.keelfy.provenance.core.model.HolderMeta;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.val;
import org.jspecify.annotations.Nullable;

/**
 * Shrinks the journal of one closed time bucket without changing what it says. Dropped stacks are only a stop between
 * two holders (a block mined, a player picking the items up), so they are folded away first: "mined -> item entity ->
 * player" becomes "mined -> player". Then events that differ in nothing but time add up into one: it starts with the
 * first and lasts until the last. Events of different chunks stay apart, which keeps "where" in the answer.
 *
 * <p>A stack that is picked up in a later bucket than it was dropped keeps its events; the rest of its life is folded
 * when that bucket closes.
 *
 * @author keelfy
 */
final class Bucketing {

    private static final String ITEM_ENTITY = "minecraft:item";

    private Bucketing() {}

    /** What an event is, apart from when it happened and how much moved. */
    private record Key(
            String cause,
            String type,
            String holder,
            @Nullable String other,
            @Nullable String item,
            @Nullable String origin,
            @Nullable String custody,
            boolean durable,
            @Nullable HolderMeta holderInfo,
            @Nullable HolderMeta otherInfo,
            @Nullable String chunk) {

        static Key of(EventRow row) {
            return new Key(
                    row.cause(),
                    row.type(),
                    row.holder(),
                    row.other(),
                    row.item(),
                    row.origin(),
                    row.custody(),
                    row.durable(),
                    row.holderInfo(),
                    row.otherInfo(),
                    row.chunk());
        }
    }

    /** Items of one kind that lie in a dropped stack, and the event that put them there. */
    private record Lying(EventRow row, long amount) {}

    /** @param rows the events of one bucket, oldest first */
    static List<EventRow> compact(List<EventRow> rows) {
        val merged = new LinkedHashMap<Key, EventRow>();
        val kept = new ArrayList<EventRow>();
        val folded = foldDrops(rows);
        val killers = killers(folded);
        for (val unmerged : folded) {
            val row = collapseMob(unmerged, killers);
            if (mergeable(row)) {
                merged.merge(Key.of(row), row, EventRow::merged);
            } else {
                kept.add(row);
            }
        }
        kept.addAll(merged.values());
        kept.sort(Comparator.comparingLong(EventRow::seq));
        return kept;
    }

    /** Who killed each creature that dropped loot: the origin its loot was minted with. */
    private static Map<String, String> killers(List<EventRow> rows) {
        val killers = new HashMap<String, String>();
        for (val row : rows) {
            if (row.type().equals("MINTED") && row.cause().equals(Cause.KILL.key())) {
                killers.putIfAbsent(row.holder(), row.origin());
            }
        }
        return killers;
    }

    /**
     * A creature that dropped loot is gone, and its events are only about what it dropped: all of one kind that one
     * killer killed in a chunk become one holder, so a farm is a line and not a line per zombie. Two killers never
     * share one, or their histories would meet in a creature neither of them dealt with together.
     */
    private static EventRow collapseMob(EventRow row, Map<String, String> killers) {
        val info = row.holderInfo();
        if (!row.cause().equals(Cause.KILL.key()) || !row.holder().startsWith("ENTITY/") || info == null) {
            return row;
        }
        val place = row.chunk() == null ? "" : "@" + row.chunk();
        val killer = killers.get(row.holder());
        return row.withHolder("ENTITY/mob:" + info.type() + place + (killer == null ? "" : "#" + killer), info);
    }

    private static boolean mergeable(EventRow row) {
        return row.type().equals("MINTED")
                || row.type().equals("TRANSFERRED")
                || row.type().equals("BURNED");
    }

    private static List<EventRow> foldDrops(List<EventRow> rows) {
        val lying = new HashMap<String, ArrayDeque<Lying>>();
        val folded = new ArrayList<EventRow>();

        for (val row : rows) {
            val transfer = row.type().equals("TRANSFERRED");
            if (row.type().equals("MINTED") && isDrop(row.holderInfo())) {
                pile(lying, row.holder(), row.item()).add(new Lying(row, row.amount()));
            } else if (transfer && isDrop(row.holderInfo())) {
                val pile = pile(lying, row.holder(), row.item());
                var left = row.amount();
                while (left > 0 && !pile.isEmpty()) {
                    val head = pile.pollFirst();
                    val taken = Math.min(left, head.amount());
                    left -= taken;
                    if (taken < head.amount()) {
                        pile.addFirst(new Lying(head.row(), head.amount() - taken));
                    }
                    val handed = hand(head.row(), taken, row);
                    if (handed == null) {
                        continue;
                    } else if (isDrop(row.otherInfo())) {
                        pile(lying, row.other(), row.item()).add(new Lying(handed, taken));
                    } else {
                        folded.add(handed);
                    }
                }
                if (left > 0) {
                    folded.add(row.withAmount(left));
                }
            } else if (transfer && isDrop(row.otherInfo())) {
                pile(lying, row.other(), row.item()).add(new Lying(row, row.amount()));
            } else {
                folded.add(row);
            }
        }
        for (val pile : lying.values()) {
            for (val rest : pile) {
                folded.add(rest.row().withAmount(rest.amount()));
            }
        }
        return folded;
    }

    /** {@code amount} of what {@code source} put on the ground went on to the holder {@code pickup} names. */
    private static @Nullable EventRow hand(EventRow source, long amount, EventRow pickup) {
        if (source.type().equals("MINTED")) {
            return source.withHolder(pickup.other(), pickup.otherInfo()).withAmount(amount);
        }
        if (source.holder().equals(pickup.other())) {
            return null; // dropped and taken back: nothing moved
        }
        return source.movedTo(pickup).withAmount(amount);
    }

    private static ArrayDeque<Lying> pile(HashMap<String, ArrayDeque<Lying>> lying, String entity, String item) {
        return lying.computeIfAbsent(entity + '\0' + item, k -> new ArrayDeque<>());
    }

    private static boolean isDrop(@Nullable HolderMeta info) {
        return info != null && ITEM_ENTITY.equals(info.type());
    }
}

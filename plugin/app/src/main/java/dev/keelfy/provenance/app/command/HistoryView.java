package dev.keelfy.provenance.app.command;

import dev.keelfy.provenance.core.event.Envelope;
import dev.keelfy.provenance.core.event.LedgerEvent;
import dev.keelfy.provenance.core.event.PoolState;
import dev.keelfy.provenance.core.model.ItemKey;
import dev.keelfy.provenance.core.model.OriginId;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.val;

/**
 * One journal entry as a line of output. Items that appeared from nowhere ({@code unknown}, {@code creative} mints) are
 * flagged: they are what a moderator looks for when hunting dupes.
 *
 * @author keelfy
 */
@RequiredArgsConstructor
public final class HistoryView {

    private static final long MINUTE = 60_000;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;

    private final Labels labels;

    public Line line(Envelope envelope, long now, int top) {
        val event = envelope.event();
        return Line.of(
                new Span(
                        age(now - envelope.time()) + " " + origin(envelope)
                                + envelope.cause().key() + " ",
                        Tone.DIM),
                new Span(describe(event, top), suspicious(event) ? Tone.ALERT : Tone.PLAIN));
    }

    /** Mints out of nowhere. */
    public static boolean suspicious(LedgerEvent event) {
        return event instanceof LedgerEvent.Minted minted
                && (minted.origin().equals(OriginId.UNKNOWN) || minted.origin().equals(OriginId.CREATIVE));
    }

    private String describe(LedgerEvent event, int top) {
        return switch (event) {
            case LedgerEvent.Minted e ->
                "+" + e.amount() + " " + name(e.item()) + " into " + labels.of(e.holder()) + " ("
                        + labels.of(e.origin()) + ")";
            case LedgerEvent.Transferred e ->
                labels.of(e.holder()) + " -> " + labels.of(e.to()) + " " + e.amount() + " " + name(e.item())
                        + (e.custody().isEmpty() ? "" : " (was held by " + labels.of(e.custody(), top) + ")");
            case LedgerEvent.Burned e -> "-" + e.amount() + " " + name(e.item()) + " from " + labels.of(e.holder());
            case LedgerEvent.Converted e ->
                labels.of(e.holder()) + " " + items(e.inputs()) + " => " + items(e.outputs());
            case LedgerEvent.Cleared e -> labels.of(e.holder()) + " cleared";
            case LedgerEvent.Synced e ->
                labels.of(e.holder()) + " arrived from " + labels.of(e.from()) + " with "
                        + e.pools().values().stream()
                                .mapToLong(PoolState::count)
                                .sum() + " items";
        };
    }

    /** {@code [farm] } for what another server of a shared database journaled. */
    private static String origin(Envelope envelope) {
        return envelope.server() == null ? "" : "[" + envelope.server() + "] ";
    }

    private String items(Map<ItemKey, Long> items) {
        return items.isEmpty()
                ? "-"
                : items.entrySet().stream()
                        .map(e -> e.getValue() + " " + name(e.getKey()))
                        .collect(Collectors.joining(" + "));
    }

    private static String name(ItemKey item) {
        val key = item.key();
        return key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key;
    }

    private static String age(long millis) {
        if (millis >= DAY) {
            return millis / DAY + "d";
        }
        if (millis >= HOUR) {
            return millis / HOUR + "h";
        }
        return Math.max(0, millis / MINUTE) + "m";
    }
}

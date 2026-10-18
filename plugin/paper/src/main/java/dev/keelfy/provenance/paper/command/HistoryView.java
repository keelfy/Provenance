package dev.keelfy.provenance.paper.command;

import dev.keelfy.provenance.core.Envelope;
import dev.keelfy.provenance.core.ItemKey;
import dev.keelfy.provenance.core.LedgerEvent;
import dev.keelfy.provenance.core.OriginId;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;
import lombok.val;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * One journal entry as a chat line. Items that appeared from nowhere ({@code unknown}, {@code creative} mints) are red:
 * they are what a moderator looks for when hunting dupes.
 *
 * @author keelfy
 */
@UtilityClass
public class HistoryView {

    private static final long MINUTE = 60_000;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;

    public Component line(Envelope envelope, long now, int top) {
        val event = envelope.event();
        val suspicious = suspicious(event);

        return Component.text(
                        age(now - envelope.time()) + " " + envelope.cause().key() + " ", NamedTextColor.DARK_GRAY)
                .append(Component.text(describe(event, top), suspicious ? NamedTextColor.RED : NamedTextColor.GRAY));
    }

    /** Mints out of nowhere. */
    public boolean suspicious(LedgerEvent event) {
        return event instanceof LedgerEvent.Minted minted
                && (minted.origin().equals(OriginId.UNKNOWN) || minted.origin().equals(OriginId.CREATIVE));
    }

    private String describe(LedgerEvent event, int top) {
        return switch (event) {
            case LedgerEvent.Minted e ->
                "+" + e.amount() + " " + name(e.item()) + " into " + Labels.of(e.holder()) + " ("
                        + Labels.of(e.origin()) + ")";
            case LedgerEvent.Transferred e ->
                Labels.of(e.holder()) + " -> " + Labels.of(e.to()) + " " + e.amount() + " " + name(e.item())
                        + (e.custody().isEmpty() ? "" : " (was held by " + Labels.of(e.custody(), top) + ")");
            case LedgerEvent.Burned e -> "-" + e.amount() + " " + name(e.item()) + " from " + Labels.of(e.holder());
            case LedgerEvent.Converted e ->
                Labels.of(e.holder()) + " " + items(e.inputs()) + " => " + items(e.outputs());
            case LedgerEvent.Cleared e -> Labels.of(e.holder()) + " cleared";
        };
    }

    private String items(Map<ItemKey, Long> items) {
        return items.isEmpty()
                ? "-"
                : items.entrySet().stream()
                        .map(e -> e.getValue() + " " + name(e.getKey()))
                        .collect(Collectors.joining(" + "));
    }

    private String name(ItemKey item) {
        val key = item.key();
        return key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key;
    }

    private String age(long millis) {
        if (millis >= DAY) {
            return millis / DAY + "d";
        }
        if (millis >= HOUR) {
            return millis / HOUR + "h";
        }
        return Math.max(0, millis / MINUTE) + "m";
    }
}

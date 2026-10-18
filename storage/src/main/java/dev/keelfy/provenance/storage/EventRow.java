package dev.keelfy.provenance.storage;

import dev.keelfy.provenance.core.Cause;
import dev.keelfy.provenance.core.Envelope;
import dev.keelfy.provenance.core.HolderId;
import dev.keelfy.provenance.core.ItemKey;
import dev.keelfy.provenance.core.Kind;
import dev.keelfy.provenance.core.LedgerEvent;
import dev.keelfy.provenance.core.OriginId;
import java.util.Map;
import java.util.TreeMap;
import lombok.val;
import org.jspecify.annotations.Nullable;

/**
 * Flat column form of an {@link Envelope}. {@code holder}/{@code other} are encoded as {@code KIND/id}. {@code detail}
 * carries the maps of a conversion as {@code |item=n|item=n|>|item=n|}; item keys never contain {@code | = >}.
 * {@code durable} marks events that involve a player or create items: they outlive plain automation.
 *
 * @author keelfy
 */
record EventRow(
        long seq,
        long time,
        String cause,
        String type,
        String holder,
        @Nullable String other,
        @Nullable String item,
        long amount,
        @Nullable String origin,
        @Nullable String custody,
        @Nullable String detail,
        boolean durable) {

    static EventRow of(Envelope envelope) {
        val seq = envelope.seq();
        val time = envelope.time();
        val cause = envelope.cause().key();
        val event = envelope.event();
        val holder = encode(event.holder());

        return switch (event) {
            case LedgerEvent.Minted e ->
                new EventRow(
                        seq,
                        time,
                        cause,
                        "MINTED",
                        holder,
                        null,
                        e.item().key(),
                        e.amount(),
                        e.origin().key(),
                        null,
                        null,
                        true);
            case LedgerEvent.Transferred e ->
                new EventRow(
                        seq,
                        time,
                        cause,
                        "TRANSFERRED",
                        holder,
                        encode(e.to()),
                        e.item().key(),
                        e.amount(),
                        null,
                        e.custody().isEmpty() ? null : VectorCodec.encode(e.custody()),
                        null,
                        e.holder().isPlayer() || e.to().isPlayer());
            case LedgerEvent.Burned e ->
                new EventRow(
                        seq,
                        time,
                        cause,
                        "BURNED",
                        holder,
                        null,
                        e.item().key(),
                        e.amount(),
                        null,
                        null,
                        null,
                        e.holder().isPlayer());
            case LedgerEvent.Converted e ->
                new EventRow(
                        seq,
                        time,
                        cause,
                        "CONVERTED",
                        holder,
                        null,
                        null,
                        0,
                        null,
                        null,
                        encode(e.inputs()) + ">" + encode(e.outputs()),
                        e.holder().isPlayer());
            case LedgerEvent.Cleared e ->
                new EventRow(
                        seq,
                        time,
                        cause,
                        "CLEARED",
                        holder,
                        null,
                        null,
                        0,
                        null,
                        null,
                        null,
                        e.holder().isPlayer());
        };
    }

    Envelope toEnvelope() {
        val holderId = decodeHolder(holder);
        val itemKey = item == null ? null : new ItemKey(item);
        val event = switch (type) {
            case "MINTED" -> new LedgerEvent.Minted(holderId, itemKey, amount, new OriginId(origin));
            case "TRANSFERRED" ->
                new LedgerEvent.Transferred(
                        holderId,
                        decodeHolder(other),
                        itemKey,
                        amount,
                        VectorCodec.decode(custody == null ? "" : custody));
            case "BURNED" -> new LedgerEvent.Burned(holderId, itemKey, amount);
            case "CONVERTED" -> {
                val split = detail.indexOf('>');
                yield new LedgerEvent.Converted(
                        holderId, decodeItems(detail.substring(0, split)), decodeItems(detail.substring(split + 1)));
            }
            case "CLEARED" -> new LedgerEvent.Cleared(holderId);
            default -> throw new IllegalStateException("unknown event type: " + type);
        };
        return new Envelope(seq, time, new Cause(cause), event);
    }

    static String encode(HolderId holder) {
        return holder.kind().name() + "/" + holder.id();
    }

    private static HolderId decodeHolder(String text) {
        val split = text.indexOf('/');
        return new HolderId(Kind.valueOf(text.substring(0, split)), text.substring(split + 1));
    }

    private static String encode(Map<ItemKey, Long> items) {
        val text = new StringBuilder("|");
        items.forEach((item, amount) ->
                text.append(item.key()).append('=').append(amount).append('|'));
        return text.toString();
    }

    private static Map<ItemKey, Long> decodeItems(String text) {
        val items = new TreeMap<ItemKey, Long>((a, b) -> a.key().compareTo(b.key()));
        for (val part : text.split("\\|")) {
            if (!part.isEmpty()) {
                val split = part.indexOf('=');
                items.put(new ItemKey(part.substring(0, split)), Long.parseLong(part.substring(split + 1)));
            }
        }
        return items;
    }
}

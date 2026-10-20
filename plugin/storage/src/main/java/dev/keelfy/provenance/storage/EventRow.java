package dev.keelfy.provenance.storage;

import dev.keelfy.provenance.core.event.Envelope;
import dev.keelfy.provenance.core.event.LedgerEvent;
import dev.keelfy.provenance.core.event.PoolState;
import dev.keelfy.provenance.core.model.Cause;
import dev.keelfy.provenance.core.model.Chunk;
import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.HolderMeta;
import dev.keelfy.provenance.core.model.ItemKey;
import dev.keelfy.provenance.core.model.Kind;
import dev.keelfy.provenance.core.model.OriginId;
import java.util.Map;
import java.util.TreeMap;
import lombok.val;
import org.jspecify.annotations.Nullable;

/**
 * Flat column form of an {@link Envelope}. {@code holder}/{@code other} are encoded as {@code KIND/id}. {@code detail}
 * carries the maps of a conversion as {@code |item=n|item=n|>|item=n|}; item keys never contain {@code | = >}. A synced
 * inventory keeps its pools in {@code detail} as {@code |item=count~origin~custody|}, the vectors in
 * {@link VectorCodec} form, and the server it came from in {@code other}. {@code durable} marks events that involve a
 * player or create items: they outlive plain automation. The type and custom name of a block or entity are stored with
 * each event ({@code holderInfo}, {@code otherInfo}): a block is only coordinates, and what stands there changes.
 * {@code chunk} (as {@code world:x:z}) says where it happened and {@code until} is the time of the last event a
 * compacted row stands for.
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
        boolean durable,
        @Nullable HolderMeta holderInfo,
        @Nullable HolderMeta otherInfo,
        long until,
        @Nullable String chunk) {

    /** Between the fields of a pool; item keys and vectors never contain it. */
    private static final String POOL_SEPARATOR = "~";

    EventRow(
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
        this(
                seq, time, cause, type, holder, other, item, amount, origin, custody, detail, durable, null, null, time,
                null);
    }

    static EventRow of(Envelope envelope) {
        val event = envelope.event();
        val other = otherOf(event);
        val place = event.holder().chunk() != null ? event.holder().chunk() : other == null ? null : other.chunk();
        return plain(envelope)
                .described(
                        event.holder().meta(), other == null ? null : other.meta(), place == null ? null : place.key());
    }

    private static @Nullable Holder otherOf(LedgerEvent event) {
        return switch (event) {
            case LedgerEvent.Transferred transfer -> transfer.to();
            case LedgerEvent.Synced synced -> synced.from();
            default -> null;
        };
    }

    EventRow withAmount(long amount) {
        return new EventRow(
                seq,
                time,
                cause,
                type,
                holder,
                other,
                item,
                amount,
                origin,
                custody,
                detail,
                durable,
                holderInfo,
                otherInfo,
                until,
                chunk);
    }

    /** The same event as seen from another holder. */
    EventRow withHolder(String holder, @Nullable HolderMeta holderInfo) {
        return new EventRow(
                seq,
                time,
                cause,
                type,
                holder,
                other,
                item,
                amount,
                origin,
                custody,
                detail,
                durable,
                holderInfo,
                otherInfo,
                until,
                chunk);
    }

    /** A transfer into a stack carried on to whoever {@code pickup} gave it to: one move, at the time of the pickup. */
    EventRow movedTo(EventRow pickup) {
        return new EventRow(
                pickup.seq,
                pickup.time,
                cause,
                type,
                holder,
                pickup.other,
                item,
                amount,
                origin,
                pickup.custody != null ? pickup.custody : custody,
                detail,
                durable || pickup.durable,
                holderInfo,
                pickup.otherInfo,
                pickup.time,
                chunk != null ? chunk : pickup.chunk);
    }

    /** This and a similar event as one: the amounts add up, the span covers both. */
    EventRow merged(EventRow similar) {
        return new EventRow(
                Math.min(seq, similar.seq),
                Math.min(time, similar.time),
                cause,
                type,
                holder,
                other,
                item,
                amount + similar.amount,
                origin,
                custody,
                detail,
                durable,
                holderInfo,
                otherInfo,
                Math.max(until, similar.until),
                chunk);
    }

    private EventRow described(
            @Nullable HolderMeta holderInfo, @Nullable HolderMeta otherInfo, @Nullable String chunk) {
        return new EventRow(
                seq,
                time,
                cause,
                type,
                holder,
                other,
                item,
                amount,
                origin,
                custody,
                detail,
                durable,
                holderInfo,
                otherInfo,
                until,
                chunk);
    }

    private static EventRow plain(Envelope envelope) {
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
            case LedgerEvent.Synced e ->
                new EventRow(
                        seq,
                        time,
                        cause,
                        "SYNCED",
                        holder,
                        encode(e.from()),
                        null,
                        0,
                        null,
                        null,
                        encodePools(e.pools()),
                        true);
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
        return toEnvelope(null);
    }

    /** @param server the server that journaled it, {@code null} if it is the one reading */
    Envelope toEnvelope(@Nullable String server) {
        val holderId = decodeHolder(holder, holderInfo);
        val itemKey = item == null ? null : new ItemKey(item);
        val event = switch (type) {
            case "MINTED" -> new LedgerEvent.Minted(holderId, itemKey, amount, new OriginId(origin));
            case "TRANSFERRED" ->
                new LedgerEvent.Transferred(
                        holderId,
                        decodeHolder(other, otherInfo),
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
            case "SYNCED" -> new LedgerEvent.Synced(holderId, decodeHolder(other, otherInfo), decodePools(detail));
            default -> throw new IllegalStateException("unknown event type: " + type);
        };
        return new Envelope(
                seq, time, until, new Cause(cause), event, chunk == null ? null : Chunk.parse(chunk), server);
    }

    static String encode(Holder holder) {
        return holder.id().key();
    }

    private static Holder decodeHolder(String text, @Nullable HolderMeta meta) {
        val split = text.indexOf('/');
        return new Holder(
                Kind.valueOf(text.substring(0, split)),
                text.substring(split + 1),
                meta == null ? HolderMeta.NONE : meta);
    }

    private static String encode(Map<ItemKey, Long> items) {
        val text = new StringBuilder("|");
        items.forEach((item, amount) ->
                text.append(item.key()).append('=').append(amount).append('|'));
        return text.toString();
    }

    private static String encodePools(Map<ItemKey, PoolState> pools) {
        val text = new StringBuilder("|");
        pools.forEach((item, pool) -> text.append(item.key())
                .append('=')
                .append(pool.count())
                .append(POOL_SEPARATOR)
                .append(VectorCodec.encode(pool.origin()))
                .append(POOL_SEPARATOR)
                .append(VectorCodec.encode(pool.custody()))
                .append('|'));
        return text.toString();
    }

    private static Map<ItemKey, PoolState> decodePools(String text) {
        val pools = new TreeMap<ItemKey, PoolState>((a, b) -> a.key().compareTo(b.key()));
        for (val part : text.split("\\|")) {
            if (!part.isEmpty()) {
                val split = part.indexOf('=');
                val fields = part.substring(split + 1).split(POOL_SEPARATOR, -1);
                pools.put(
                        new ItemKey(part.substring(0, split)),
                        new PoolState(
                                Long.parseLong(fields[0]),
                                VectorCodec.decode(fields[1]),
                                VectorCodec.decode(fields[2])));
            }
        }
        return pools;
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

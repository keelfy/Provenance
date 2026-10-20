package dev.keelfy.provenance.app.web;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.keelfy.provenance.app.command.HistoryView;
import dev.keelfy.provenance.core.event.Envelope;
import dev.keelfy.provenance.core.event.LedgerEvent;
import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.ItemKey;
import dev.keelfy.provenance.core.model.OriginId;
import dev.keelfy.provenance.core.taint.TaintVector;
import dev.keelfy.provenance.storage.Trace;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import lombok.experimental.UtilityClass;
import lombok.val;
import org.jspecify.annotations.Nullable;

/**
 * The {@code Report} v1 snapshot the web viewer renders (see {@code WEB-PLAN.md}). Holders are {@code KIND/id}; names
 * of players, blocks and origins are resolved here because the web server has no Bukkit.
 *
 * @author keelfy
 */
@UtilityClass
public class ReportJson {

    private static final int VERSION = 1;
    private static final Gson GSON = new Gson();

    /** What the report answers: {@code kind} is e.g. {@code history.player}. */
    public record Query(String kind, Holder root, @Nullable ItemKey item, int depth) {}

    public String build(
            Query query,
            Trace trace,
            long now,
            long ttlMillis,
            Function<Holder, String> holderLabel,
            Function<OriginId, String> originLabel) {
        // Events come oldest first, so the table ends up with what each holder was last; an event that saw it
        // differently says so itself.
        val shown = trace.events().stream().filter(ReportJson::isShown).toList();
        val holders = new LinkedHashMap<String, Holder>();
        holders.put(id(query.root()), query.root());
        for (val envelope : shown) {
            register(envelope.event().holder(), holders);
            if (envelope.event() instanceof LedgerEvent.Transferred transfer) {
                register(transfer.to(), holders);
            }
        }
        val events = new JsonArray();
        for (val envelope : shown) {
            events.add(event(envelope, holders, holderLabel, originLabel));
        }

        val holderTable = new JsonObject();
        holders.forEach((id, holder) -> {
            val entry = new JsonObject();
            entry.addProperty("label", holderLabel.apply(holder));
            entry.addProperty("kind", holder.id().kind().name());
            if (holder.type() != null) {
                entry.addProperty("type", holder.type());
            }
            holderTable.add(id, entry);
        });

        val queryJson = new JsonObject();
        queryJson.addProperty("kind", query.kind());
        queryJson.addProperty("root", id(query.root()));
        if (query.item() != null) {
            queryJson.addProperty("item", query.item().key());
        }
        queryJson.addProperty("depth", query.depth());

        val report = new JsonObject();
        report.addProperty("v", VERSION);
        report.addProperty("created", now);
        report.addProperty("expires", now + ttlMillis);
        report.add("query", queryJson);
        report.add("holders", holderTable);
        report.add("events", events);
        report.addProperty("truncated", trace.truncated());
        return GSON.toJson(report);
    }

    private JsonObject event(
            Envelope envelope,
            Map<String, Holder> holders,
            Function<Holder, String> holderLabel,
            Function<OriginId, String> originLabel) {
        val event = envelope.event();
        val json = new JsonObject();
        json.addProperty("seq", envelope.seq());
        json.addProperty("time", envelope.time());
        json.addProperty("cause", envelope.cause().key());
        json.addProperty("holder", id(event.holder()));
        asSeen("holderAs", event.holder(), holders, holderLabel, json);
        json.addProperty("suspicious", HistoryView.suspicious(event));
        if (envelope.until() > envelope.time()) {
            json.addProperty("until", envelope.until());
        }
        if (envelope.chunk() != null) {
            val chunk = new JsonObject();
            chunk.addProperty("world", envelope.chunk().world());
            chunk.addProperty("x", envelope.chunk().x());
            chunk.addProperty("z", envelope.chunk().z());
            json.add("chunk", chunk);
        }

        switch (event) {
            case LedgerEvent.Minted e -> {
                json.addProperty("type", "MINTED");
                json.addProperty("item", e.item().key());
                json.addProperty("amount", e.amount());
                json.addProperty("origin", originLabel.apply(e.origin()));
            }
            case LedgerEvent.Transferred e -> {
                json.addProperty("type", "TRANSFERRED");
                json.addProperty("other", id(e.to()));
                asSeen("otherAs", e.to(), holders, holderLabel, json);
                json.addProperty("item", e.item().key());
                json.addProperty("amount", e.amount());
                if (!e.custody().isEmpty()) {
                    json.add("custody", shares(e.custody(), originLabel));
                }
            }
            case LedgerEvent.Burned e -> {
                json.addProperty("type", "BURNED");
                json.addProperty("item", e.item().key());
                json.addProperty("amount", e.amount());
            }
            case LedgerEvent.Converted e -> {
                json.addProperty("type", "CONVERTED");
                json.add("inputs", items(e.inputs()));
                json.add("outputs", items(e.outputs()));
            }
            case LedgerEvent.Cleared e -> json.addProperty("type", "CLEARED");
            case LedgerEvent.Synced e -> throw new IllegalArgumentException("not part of the report: " + e);
        }
        return json;
    }

    /** The viewer does not know inventories carried over from another server yet. */
    private boolean isShown(Envelope envelope) {
        return !(envelope.event() instanceof LedgerEvent.Synced);
    }

    /** The newest sighting wins, but one without info does not wipe out one with. */
    private void register(Holder holder, Map<String, Holder> holders) {
        holders.merge(id(holder), holder, (known, seen) -> seen.type() != null || known.type() == null ? seen : known);
    }

    /** Adds {@code key}: what the event saw, when it differs from the holder's entry in the table. */
    private void asSeen(
            String key,
            Holder seen,
            Map<String, Holder> holders,
            Function<Holder, String> holderLabel,
            JsonObject json) {

        if (Objects.equals(seen.type(), holders.get(id(seen)).type())) {
            return;
        }

        val as = new JsonObject();
        as.addProperty("label", holderLabel.apply(seen));
        if (seen.type() != null) {
            as.addProperty("type", seen.type());
        }
        json.add(key, as);
    }

    private String id(Holder holder) {
        return holder.id().key();
    }

    /** Origin label to share; origins that share a label add up. */
    private JsonObject shares(TaintVector vector, Function<OriginId, String> originLabel) {
        val merged = new LinkedHashMap<String, Double>();
        vector.shares().forEach((origin, share) -> merged.merge(originLabel.apply(origin), share, Double::sum));
        val json = new JsonObject();
        merged.forEach(json::addProperty);
        return json;
    }

    private JsonObject items(Map<ItemKey, Long> items) {
        val json = new JsonObject();
        items.forEach((item, amount) -> json.addProperty(item.key(), amount));
        return json;
    }
}

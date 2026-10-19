package dev.keelfy.provenance.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonParser;
import dev.keelfy.provenance.core.event.Envelope;
import dev.keelfy.provenance.core.event.LedgerEvent;
import dev.keelfy.provenance.core.model.Cause;
import dev.keelfy.provenance.core.model.Chunk;
import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.ItemKey;
import dev.keelfy.provenance.core.model.OriginId;
import dev.keelfy.provenance.core.taint.TaintVector;
import dev.keelfy.provenance.storage.Trace;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.val;
import org.junit.jupiter.api.Test;

class ReportJsonTest {

    private static final ItemKey DIAMOND = new ItemKey("minecraft:diamond");

    private final UUID aliceId = UUID.randomUUID();
    private final Holder alice = Holder.player(aliceId);
    private final Holder chest = Holder.block("world", 1, 64, 2).described("minecraft:chest");
    private final Holder furnace = Holder.block("world", 1, 64, 2).described("minecraft:furnace");

    @SuppressWarnings("null")
    @Test
    void snapshotCarriesEventsLabelsAndSuspiciousMints() {
        val events = List.of(
                new Envelope(1, 1000, Cause.MINE, new LedgerEvent.Minted(furnace, DIAMOND, 5, OriginId.CREATIVE)),
                new Envelope(
                        2,
                        2000,
                        Cause.CONTAINER,
                        new LedgerEvent.Transferred(
                                chest, alice, DIAMOND, 3, TaintVector.of(OriginId.player(aliceId)))),
                new Envelope(
                        3,
                        3000,
                        Cause.CRAFT,
                        new LedgerEvent.Converted(alice, Map.of(DIAMOND, 3L), Map.of(new ItemKey("a:b"), 1L))));
        val query = new ReportJson.Query("history.player", alice, DIAMOND, 2);

        val json = JsonParser.parseString(ReportJson.build(
                        query,
                        new Trace(events, true),
                        10_000,
                        5_000,
                        holder -> holder.isPlayer() ? "Alice" : holder.displayName(),
                        origin -> origin.isPlayer() ? "Alice" : origin.key()))
                .getAsJsonObject();

        assertThat(json.get("v").getAsInt()).isEqualTo(1);
        assertThat(json.get("expires").getAsLong()).isEqualTo(15_000);
        assertThat(json.get("truncated").getAsBoolean()).isTrue();
        assertThat(json.getAsJsonObject("query").get("item").getAsString()).isEqualTo("minecraft:diamond");

        val holders = json.getAsJsonObject("holders");
        assertThat(holders.keySet()).containsExactlyInAnyOrder("PLAYER/" + aliceId, "BLOCK/world:1:64:2");
        assertThat(holders.getAsJsonObject("PLAYER/" + aliceId).get("label").getAsString())
                .isEqualTo("Alice");
        assertThat(holders.getAsJsonObject("PLAYER/" + aliceId).has("type")).isFalse();
        val block = holders.getAsJsonObject("BLOCK/world:1:64:2");
        assertThat(block.get("type").getAsString()).isEqualTo("minecraft:chest");
        assertThat(block.get("label").getAsString()).isEqualTo("Chest");

        val list = json.getAsJsonArray("events");
        val mint = list.get(0).getAsJsonObject();
        assertThat(mint.get("type").getAsString()).isEqualTo("MINTED");
        assertThat(mint.get("suspicious").getAsBoolean()).isTrue();
        // the block was a furnace when it happened, a chest later
        assertThat(mint.getAsJsonObject("holderAs").get("type").getAsString()).isEqualTo("minecraft:furnace");
        assertThat(mint.getAsJsonObject("holderAs").get("label").getAsString()).isEqualTo("Furnace");
        val transfer = list.get(1).getAsJsonObject();
        assertThat(transfer.get("other").getAsString()).isEqualTo("PLAYER/" + aliceId);
        assertThat(transfer.getAsJsonObject("custody").get("Alice").getAsDouble())
                .isEqualTo(1.0);
        assertThat(transfer.get("suspicious").getAsBoolean()).isFalse();
        assertThat(transfer.has("holderAs")).isFalse();
        val converted = list.get(2).getAsJsonObject();
        assertThat(converted.getAsJsonObject("inputs").get("minecraft:diamond").getAsLong())
                .isEqualTo(3);
        assertThat(converted.has("amount")).isFalse();
    }

    @SuppressWarnings("null")
    @Test
    void mergedEventCarriesItsSpanAndChunk() {
        val merged = new Envelope(
                1,
                1000,
                5000,
                Cause.MINE,
                new LedgerEvent.Minted(alice, DIAMOND, 640, OriginId.player(aliceId)),
                new Chunk("world", -3, 12));
        val json = JsonParser.parseString(ReportJson.build(
                        new ReportJson.Query("history.player", alice, null, 1),
                        new Trace(List.of(merged), false),
                        10_000,
                        5_000,
                        holder -> "Alice",
                        origin -> "Alice"))
                .getAsJsonObject();

        val event = json.getAsJsonArray("events").get(0).getAsJsonObject();
        assertThat(event.get("until").getAsLong()).isEqualTo(5000);
        assertThat(event.getAsJsonObject("chunk").get("world").getAsString()).isEqualTo("world");
        assertThat(event.getAsJsonObject("chunk").get("x").getAsInt()).isEqualTo(-3);
        assertThat(event.getAsJsonObject("chunk").get("z").getAsInt()).isEqualTo(12);
    }
}

package dev.keelfy.provenance.app.command;

import dev.keelfy.provenance.app.ProvenanceStorage;
import dev.keelfy.provenance.app.Settings;
import dev.keelfy.provenance.app.inventory.InventoryReconciler;
import dev.keelfy.provenance.app.port.Log;
import dev.keelfy.provenance.app.port.OnlinePlayer;
import dev.keelfy.provenance.app.port.PlayerDirectory;
import dev.keelfy.provenance.app.port.Scheduler;
import dev.keelfy.provenance.app.web.ReportJson;
import dev.keelfy.provenance.app.web.ReportPublisher;
import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.core.analysis.Correlation;
import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.ItemKey;
import dev.keelfy.provenance.core.model.OriginId;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import lombok.val;
import org.jspecify.annotations.Nullable;

/**
 * What {@code /prov hand | block | player <name> | links [name] | history block | history player <name> [item]} do:
 * read-only views over the ledger and its journal. {@code web history ...} posts the same history as a snapshot to the
 * web viewer and prints a one-time link; {@code web revoke <token>} kills a link. The platform parses the command and
 * checks permissions; every method answers through the {@link Sender} and returns whether it succeeded.
 *
 * @author keelfy
 */
public final class ProvService {

    private static final String WEB_OFF = "Web viewer is off: set web.endpoint and web.secret in config.yml";
    private static final String LOOK_AT_CONTAINER = "Look at a container";
    private static final String UNKNOWN_PLAYER = "Unknown player";

    private final Ledger ledger;
    private final Correlation correlation;
    private final InventoryReconciler reconciler;
    private final ProvenanceStorage storage;
    private final ReportPublisher publisher;
    private final PlayerDirectory players;
    private final Scheduler scheduler;
    private final Log log;
    private final Settings settings;
    private final Labels labels;
    private final HistoryView history;

    public ProvService(
            Ledger ledger,
            InventoryReconciler reconciler,
            ProvenanceStorage storage,
            ReportPublisher publisher,
            PlayerDirectory players,
            Scheduler scheduler,
            Log log,
            Settings settings) {
        this.ledger = ledger;
        this.correlation = new Correlation(ledger);
        this.reconciler = reconciler;
        this.storage = storage;
        this.publisher = publisher;
        this.players = players;
        this.scheduler = scheduler;
        this.log = log;
        this.settings = settings;
        this.labels = new Labels(players);
        this.history = new HistoryView(labels);
    }

    public boolean hand(Sender sender) {
        val player = playerOnly(sender);
        if (player == null) {
            return false;
        }

        val key = player.mainHand();
        if (key == null) {
            return error(sender, "Main hand is empty");
        }

        reconciler.reconcile(player);
        val pool = ledger.pool(player.holder(), key);
        if (pool == null) {
            return error(sender, "No tracked " + key.key());
        }
        sender.reply(key.key() + " ×" + pool.getCount() + " (all you carry)");
        sender.reply("  origin: " + labels.of(pool.getOrigin(), settings.topOrigins()));
        return true;
    }

    public boolean block(Sender sender) {
        val player = playerOnly(sender);
        if (player == null) {
            return false;
        }

        val holder = player.lookingAt(settings.blockReach());
        if (holder == null) {
            return error(sender, LOOK_AT_CONTAINER);
        }

        val pools = ledger.getPools(holder);
        if (pools.isEmpty()) {
            return error(sender, "Nothing tracked in " + holder.id());
        }
        sender.reply(holder.id().id());
        pools.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.comparingLong(pool -> -pool.getCount())))
                .limit(settings.blockRows())
                .forEach(entry -> {
                    val pool = entry.getValue();
                    sender.reply(entry.getKey().key() + " ×" + pool.getCount());
                    sender.reply("  origin: " + labels.of(pool.getOrigin(), settings.topOrigins()));
                    sender.reply("  custody: " + labels.of(pool.getCustody(), settings.topOrigins()));
                });
        return true;
    }

    public boolean player(Sender sender, String name) {
        val target = resolve(name);
        if (target == null) {
            return error(sender, UNKNOWN_PLAYER);
        }

        val wealth = correlation.wealth(target);
        sender.reply(labels.of(target) + " holds, by origin:");
        wealth.entrySet().stream()
                .sorted(Map.Entry.<OriginId, Double>comparingByValue().reversed())
                .limit(settings.playerRows())
                .forEach(entry -> sender.reply("  " + labels.of(entry.getKey()) + " " + format(entry.getValue())));
        sender.reply("  share: " + labels.ofAmounts(wealth, settings.topOrigins()));
        return true;
    }

    /** @param name the player to look at, {@code null} for the sender */
    public boolean links(Sender sender, @Nullable String name) {
        val self = sender.player();
        val target = name != null ? resolve(name) : self != null ? self.origin() : null;
        if (target == null) {
            return error(sender, "Name a player");
        }

        val links = correlation.links(target);
        if (links.isEmpty()) {
            return error(sender, "No links for " + labels.of(target));
        }
        sender.reply("Links of " + labels.of(target) + " (sent / received / similarity):");
        links.stream()
                .limit(settings.linkRows())
                .forEach(link -> sender.reply("  " + labels.of(link.other()) + " " + format(link.sent()) + " / "
                        + format(link.received()) + " / " + format(link.similarity())));
        return true;
    }

    public boolean historyBlock(Sender sender) {
        val holder = lookedAtContainer(sender);
        return holder != null && showHistory(sender, holder, null);
    }

    public boolean historyPlayer(Sender sender, String name, @Nullable String item) {
        val target = resolve(name);
        if (target == null) {
            return error(sender, UNKNOWN_PLAYER);
        }
        return showHistory(sender, Holder.player(target.playerId()), itemKey(item));
    }

    public boolean webHistoryBlock(Sender sender) {
        val holder = lookedAtContainer(sender);
        return holder != null && publish(sender, "history.block", holder, null);
    }

    public boolean webHistoryPlayer(Sender sender, String name, @Nullable String item) {
        val target = resolve(name);
        if (target == null) {
            return error(sender, UNKNOWN_PLAYER);
        }
        return publish(sender, "history.player", Holder.player(target.playerId()), itemKey(item));
    }

    public boolean webRevoke(Sender sender, String token) {
        if (!settings.webEnabled()) {
            return error(sender, WEB_OFF);
        }

        publisher
                .revoke(token)
                .whenComplete((ignored, failure) -> onMain(() -> {
                    if (failure != null) {
                        log.warning("Could not revoke link", cause(failure));
                        error(sender, "Could not revoke: " + cause(failure).getMessage());
                    } else {
                        sender.reply("Link revoked");
                    }
                }));
        return true;
    }

    /** Queries on the writer thread, prints back on the main one. */
    private boolean showHistory(Sender sender, Holder holder, @Nullable ItemKey item) {
        storage.history(holder, item, settings.historyRows())
                .whenComplete((events, failure) -> onMain(() -> {
                    if (failure != null) {
                        error(sender, "History unavailable: " + failure.getMessage());
                    } else if (events.isEmpty()) {
                        error(sender, "No history for " + labels.of(holder));
                    } else {
                        val now = System.currentTimeMillis();
                        sender.reply("History of " + labels.of(holder) + " (newest first):");
                        events.forEach(event -> sender.send(history.line(event, now, settings.topOrigins())));
                    }
                }));
        return true;
    }

    /** Traces on the writer thread, builds the snapshot on the main one (names), uploads off both. */
    private boolean publish(Sender sender, String kind, Holder root, @Nullable ItemKey item) {
        if (!settings.webEnabled()) {
            return error(sender, WEB_OFF);
        }

        val query = new ReportJson.Query(kind, root, item, settings.webDepth());
        storage.trace(root, item, settings.webDepth(), settings.webMaxEvents())
                .whenComplete((trace, failure) -> onMain(() -> {
                    if (failure != null) {
                        log.warning("Could not trace history for " + root, failure);
                        error(sender, "History unavailable: " + failure.getMessage());
                    } else if (trace.events().isEmpty()) {
                        error(sender, "No history for " + labels.of(root));
                    } else {
                        val json = ReportJson.build(
                                query,
                                trace,
                                System.currentTimeMillis(),
                                TimeUnit.HOURS.toMillis(settings.webTtlHours()),
                                labels::name,
                                labels::of);
                        publisher
                                .publish(json)
                                .whenComplete((link, error) -> onMain(() -> {
                                    if (error != null) {
                                        log.warning("Could not publish report", cause(error));
                                        error(
                                                sender,
                                                "Could not publish: "
                                                        + cause(error).getMessage());
                                    } else {
                                        sender.send(Line.of(
                                                new Span("Report ready: ", Tone.PLAIN),
                                                new Span("[open]", Tone.ACCENT, link)));
                                    }
                                }));
                    }
                }));
        return true;
    }

    private void onMain(Runnable action) {
        scheduler.runNextTick(action);
    }

    private @Nullable Holder lookedAtContainer(Sender sender) {
        val player = playerOnly(sender);
        if (player == null) {
            return null;
        }

        val holder = player.lookingAt(settings.blockReach());
        if (holder == null) {
            error(sender, LOOK_AT_CONTAINER);
        }
        return holder;
    }

    private @Nullable OriginId resolve(String name) {
        val id = players.idOf(name);
        return id != null ? OriginId.player(id) : null;
    }

    private static @Nullable ItemKey itemKey(@Nullable String name) {
        if (name == null) {
            return null;
        }
        val lower = name.toLowerCase(Locale.ROOT);
        return new ItemKey(lower.contains(":") ? lower : "minecraft:" + lower);
    }

    private static @Nullable OnlinePlayer playerOnly(Sender sender) {
        val player = sender.player();
        if (player == null) {
            error(sender, "Players only");
        }
        return player;
    }

    /** Async stages wrap the real error in a {@link CompletionException}. */
    private static Throwable cause(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
    }

    private static boolean error(Sender sender, String message) {
        sender.send(Line.alert(message));
        return false;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}

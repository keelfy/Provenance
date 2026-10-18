package dev.keelfy.provenance.paper.command;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.keelfy.provenance.ProvenanceStorage;
import dev.keelfy.provenance.Settings;
import dev.keelfy.provenance.core.Correlation;
import dev.keelfy.provenance.core.HolderId;
import dev.keelfy.provenance.core.ItemKey;
import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.core.OriginId;
import dev.keelfy.provenance.paper.Holders;
import dev.keelfy.provenance.paper.ItemKeys;
import dev.keelfy.provenance.paper.inventory.InventoryReconciler;
import dev.keelfy.provenance.paper.web.ReportJson;
import dev.keelfy.provenance.paper.web.ReportPublisher;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import lombok.val;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.block.Container;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * {@code /prov hand | block | player <name> | links [name] | history block | history player <name> [item]}: read-only
 * views over the ledger and its journal. {@code /prov web history ...} posts the same history as a snapshot to the web
 * viewer and prints a one-time link; {@code /prov web revoke <token>} kills a link.
 *
 * @author keelfy
 */
@Singleton
public final class ProvCommand {

    public static final String PERMISSION = "provenance.admin";
    public static final String WEB_PERMISSION = "provenance.web";

    private static final String PLAYER_ARG = "player";
    private static final String ITEM_ARG = "item";
    private static final String TOKEN_ARG = "token";

    private final Ledger ledger;
    private final Correlation correlation;
    private final InventoryReconciler reconciler;
    private final ProvenanceStorage storage;
    private final Plugin plugin;
    private final Settings settings;
    private final ReportPublisher publisher;

    @Inject
    public ProvCommand(
            Ledger ledger,
            InventoryReconciler reconciler,
            ProvenanceStorage storage,
            Plugin plugin,
            Settings settings,
            ReportPublisher publisher) {
        this.ledger = ledger;
        this.correlation = new Correlation(ledger);
        this.reconciler = reconciler;
        this.storage = storage;
        this.plugin = plugin;
        this.settings = settings;
        this.publisher = publisher;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("prov")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .then(Commands.literal("hand").executes(this::hand))
                .then(Commands.literal("block").executes(this::block))
                .then(Commands.literal("player")
                        .then(Commands.argument(PLAYER_ARG, StringArgumentType.word())
                                .suggests(this::suggestPlayers)
                                .executes(this::player)))
                .then(history(this::historyBlock, this::historyPlayer))
                .then(Commands.literal("links")
                        .executes(this::links)
                        .then(Commands.argument(PLAYER_ARG, StringArgumentType.word())
                                .suggests(this::suggestPlayers)
                                .executes(this::links)))
                .then(Commands.literal("web")
                        .requires(source -> source.getSender().hasPermission(WEB_PERMISSION))
                        .then(history(this::webHistoryBlock, this::webHistoryPlayer))
                        .then(Commands.literal("revoke")
                                .then(Commands.argument(TOKEN_ARG, StringArgumentType.word())
                                        .executes(this::webRevoke))))
                .build();
    }

    private LiteralArgumentBuilder<CommandSourceStack> history(
            Command<CommandSourceStack> block, Command<CommandSourceStack> player) {
        return Commands.literal("history")
                .then(Commands.literal("block").executes(block))
                .then(Commands.literal("player")
                        .then(Commands.argument(PLAYER_ARG, StringArgumentType.word())
                                .suggests(this::suggestPlayers)
                                .executes(player)
                                .then(Commands.argument(ITEM_ARG, StringArgumentType.word())
                                        .executes(player))));
    }

    private int hand(CommandContext<CommandSourceStack> context) {
        val player = playerSender(context);
        if (player == null) {
            return 0;
        }

        val key = ItemKeys.of(player.getInventory().getItemInMainHand());
        if (key == null) {
            return error(player, "Main hand is empty");
        }

        reconciler.reconcile(player);
        val pool = ledger.pool(Holders.of(player), key);
        if (pool == null) {
            return error(player, "No tracked " + key.key());
        }
        reply(player, key.key() + " ×" + pool.getCount() + " (all you carry)");
        reply(player, "  origin: " + Labels.of(pool.getOrigin(), settings.topOrigins()));
        return 1;
    }

    private int block(CommandContext<CommandSourceStack> context) {
        val player = playerSender(context);
        if (player == null) {
            return 0;
        }

        val holder = lookedAtContainer(player);
        if (holder == null) {
            return error(player, "Look at a container");
        }

        val pools = ledger.getPools(holder);
        if (pools.isEmpty()) {
            return error(player, "Nothing tracked in " + holder.id());
        }
        reply(player, holder.id());
        pools.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.comparingLong(pool -> -pool.getCount())))
                .limit(settings.blockRows())
                .forEach(entry -> {
                    val pool = entry.getValue();
                    reply(player, entry.getKey().key() + " ×" + pool.getCount());
                    reply(player, "  origin: " + Labels.of(pool.getOrigin(), settings.topOrigins()));
                    reply(player, "  custody: " + Labels.of(pool.getCustody(), settings.topOrigins()));
                });
        return 1;
    }

    private @Nullable HolderId lookedAtContainer(Player player) {
        val target = player.getTargetBlockExact(settings.blockReach());
        return target != null && target.getState(false) instanceof Container container
                ? Holders.of(container.getInventory())
                : null;
    }

    private int player(CommandContext<CommandSourceStack> context) {
        val sender = context.getSource().getSender();
        val target = argumentPlayer(context);
        if (target == null) {
            return error(sender, "Unknown player");
        }

        val wealth = correlation.wealth(target);
        reply(sender, Labels.of(target) + " holds, by origin:");
        wealth.entrySet().stream()
                .sorted(Map.Entry.<OriginId, Double>comparingByValue().reversed())
                .limit(settings.playerRows())
                .forEach(entry -> reply(sender, "  " + Labels.of(entry.getKey()) + " " + format(entry.getValue())));
        reply(sender, "  share: " + Labels.ofAmounts(wealth, settings.topOrigins()));
        return 1;
    }

    private int historyBlock(CommandContext<CommandSourceStack> context) {
        val player = playerSender(context);
        if (player == null) {
            return 0;
        }

        val holder = lookedAtContainer(player);
        if (holder == null) {
            return error(player, "Look at a container");
        }
        return showHistory(player, holder, null);
    }

    private int historyPlayer(CommandContext<CommandSourceStack> context) {
        val sender = context.getSource().getSender();
        val target = argumentPlayer(context);
        if (target == null) {
            return error(sender, "Unknown player");
        }

        val item = hasArgument(context, ITEM_ARG) ? itemArgument(context) : null;
        return showHistory(sender, HolderId.player(target.playerId()), item);
    }

    /** Queries on the writer thread, prints back on the main one. */
    private int showHistory(CommandSender sender, HolderId holder, @Nullable ItemKey item) {
        storage.history(holder, item, settings.historyRows())
                .whenComplete((events, failure) -> onMain(() -> {
                    if (failure != null) {
                        error(sender, "History unavailable: " + failure.getMessage());
                    } else if (events.isEmpty()) {
                        error(sender, "No history for " + Labels.of(holder));
                    } else {
                        val now = System.currentTimeMillis();
                        reply(sender, "History of " + Labels.of(holder) + " (newest first):");
                        events.forEach(
                                event -> sender.sendMessage(HistoryView.line(event, now, settings.topOrigins())));
                    }
                }));
        return 1;
    }

    private int webHistoryBlock(CommandContext<CommandSourceStack> context) {
        val player = playerSender(context);
        if (player == null) {
            return 0;
        }

        val holder = lookedAtContainer(player);
        if (holder == null) {
            return error(player, "Look at a container");
        }
        return publish(player, "history.block", holder, null);
    }

    private int webHistoryPlayer(CommandContext<CommandSourceStack> context) {
        val sender = context.getSource().getSender();
        val target = argumentPlayer(context);
        if (target == null) {
            return error(sender, "Unknown player");
        }

        val item = hasArgument(context, ITEM_ARG) ? itemArgument(context) : null;
        return publish(sender, "history.player", HolderId.player(target.playerId()), item);
    }

    /** Traces on the writer thread, builds the snapshot on the main one (names), uploads off both. */
    private int publish(CommandSender sender, String kind, HolderId root, @Nullable ItemKey item) {
        if (!settings.webEnabled()) {
            return error(sender, "Web viewer is off: set web.endpoint and web.secret in config.yml");
        }

        val query = new ReportJson.Query(kind, root, item, settings.webDepth());
        storage.trace(root, item, settings.webDepth(), settings.webMaxEvents())
                .whenComplete((trace, failure) -> onMain(() -> {
                    if (failure != null) {
                        plugin.getLogger().log(Level.WARNING, "Could not trace history for " + root, failure);
                        error(sender, "History unavailable: " + failure.getMessage());
                    } else if (trace.events().isEmpty()) {
                        error(sender, "No history for " + Labels.of(root));
                    } else {
                        val json = ReportJson.build(
                                query,
                                trace,
                                System.currentTimeMillis(),
                                TimeUnit.HOURS.toMillis(settings.webTtlHours()),
                                holder -> Labels.name(holder, holder.info()),
                                Labels::of);
                        publisher
                                .publish(json)
                                .whenComplete((link, error) -> onMain(() -> {
                                    if (error != null) {
                                        plugin.getLogger().log(Level.WARNING, "Could not publish report", cause(error));
                                        error(
                                                sender,
                                                "Could not publish: "
                                                        + cause(error).getMessage());
                                    } else {
                                        sender.sendMessage(Component.text("Report ready: ", NamedTextColor.GRAY)
                                                .append(Component.text("[open]", NamedTextColor.AQUA)
                                                        .clickEvent(ClickEvent.openUrl(link))));
                                    }
                                }));
                    }
                }));
        return 1;
    }

    private int webRevoke(CommandContext<CommandSourceStack> context) {
        val sender = context.getSource().getSender();
        if (!settings.webEnabled()) {
            return error(sender, "Web viewer is off: set web.endpoint and web.secret in config.yml");
        }

        publisher
                .revoke(StringArgumentType.getString(context, TOKEN_ARG))
                .whenComplete((ignored, failure) -> onMain(() -> {
                    if (failure != null) {
                        plugin.getLogger().log(Level.WARNING, "Could not revoke link", cause(failure));
                        error(sender, "Could not revoke: " + cause(failure).getMessage());
                    } else {
                        reply(sender, "Link revoked");
                    }
                }));
        return 1;
    }

    private void onMain(Runnable action) {
        plugin.getServer().getScheduler().runTask(plugin, action);
    }

    /** Async stages wrap the real error in a {@link java.util.concurrent.CompletionException}. */
    private static Throwable cause(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
    }

    private static ItemKey itemArgument(CommandContext<CommandSourceStack> context) {
        val name = StringArgumentType.getString(context, ITEM_ARG).toLowerCase(Locale.ROOT);
        return new ItemKey(name.contains(":") ? name : "minecraft:" + name);
    }

    private int links(CommandContext<CommandSourceStack> context) {
        val sender = context.getSource().getSender();
        val target = hasArgument(context) ? argumentPlayer(context) : senderOrigin(sender);
        if (target == null) {
            return error(sender, "Name a player");
        }

        val links = correlation.links(target);
        if (links.isEmpty()) {
            return error(sender, "No links for " + Labels.of(target));
        }
        reply(sender, "Links of " + Labels.of(target) + " (sent / received / similarity):");
        links.stream()
                .limit(settings.linkRows())
                .forEach(link -> reply(
                        sender,
                        "  " + Labels.of(link.other()) + " " + format(link.sent()) + " / " + format(link.received())
                                + " / " + format(link.similarity())));
        return 1;
    }

    private @Nullable OriginId argumentPlayer(CommandContext<CommandSourceStack> context) {
        val name = StringArgumentType.getString(context, PLAYER_ARG);
        val player = Bukkit.getOfflinePlayerIfCached(name);
        return player != null ? OriginId.player(player.getUniqueId()) : null;
    }

    private static boolean hasArgument(CommandContext<CommandSourceStack> context) {
        return hasArgument(context, PLAYER_ARG);
    }

    private static boolean hasArgument(CommandContext<CommandSourceStack> context, String name) {
        return context.getNodes().stream()
                .anyMatch(node -> node.getNode().getName().equals(name));
    }

    private static @Nullable OriginId senderOrigin(CommandSender sender) {
        return sender instanceof Player player ? OriginId.player(player.getUniqueId()) : null;
    }

    private @Nullable Player playerSender(CommandContext<CommandSourceStack> context) {
        val sender = context.getSource().getSender();
        if (sender instanceof Player player) {
            return player;
        }
        error(sender, "Players only");
        return null;
    }

    private CompletableFuture<Suggestions> suggestPlayers(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        val prefix = builder.getRemainingLowerCase();
        Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .forEach(builder::suggest);
        return builder.buildFuture();
    }

    private static int error(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.RED));
        return 0;
    }

    private static void reply(CommandSender sender, String line) {
        sender.sendMessage(Component.text(line, NamedTextColor.GRAY));
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}

package dev.keelfy.provenance.paper.command;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.mojang.brigadier.arguments.StringArgumentType;
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
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.val;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.block.Container;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * {@code /prov hand | block | player <name> | links [name] | history block | history player <name> [item]}: read-only
 * views over the ledger and its journal.
 *
 * @author keelfy
 */
@Singleton
public final class ProvCommand {

    public static final String PERMISSION = "provenance.admin";

    private static final String PLAYER_ARG = "player";
    private static final String ITEM_ARG = "item";

    private final Ledger ledger;
    private final Correlation correlation;
    private final InventoryReconciler reconciler;
    private final ProvenanceStorage storage;
    private final Plugin plugin;
    private final Settings settings;

    @Inject
    public ProvCommand(
            Ledger ledger,
            InventoryReconciler reconciler,
            ProvenanceStorage storage,
            Plugin plugin,
            Settings settings) {
        this.ledger = ledger;
        this.correlation = new Correlation(ledger);
        this.reconciler = reconciler;
        this.storage = storage;
        this.plugin = plugin;
        this.settings = settings;
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
                .then(Commands.literal("history")
                        .then(Commands.literal("block").executes(this::historyBlock))
                        .then(Commands.literal("player")
                                .then(Commands.argument(PLAYER_ARG, StringArgumentType.word())
                                        .suggests(this::suggestPlayers)
                                        .executes(this::historyPlayer)
                                        .then(Commands.argument(ITEM_ARG, StringArgumentType.word())
                                                .executes(this::historyPlayer)))))
                .then(Commands.literal("links")
                        .executes(this::links)
                        .then(Commands.argument(PLAYER_ARG, StringArgumentType.word())
                                .suggests(this::suggestPlayers)
                                .executes(this::links)))
                .build();
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

        val target = player.getTargetBlockExact(settings.blockReach());
        val holder = target != null && target.getState(false) instanceof Container container
                ? Holders.of(container.getInventory())
                : null;
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

        val target = player.getTargetBlockExact(settings.blockReach());
        val holder = target != null && target.getState(false) instanceof Container container
                ? Holders.of(container.getInventory())
                : null;
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
                .whenComplete((events, failure) -> plugin.getServer()
                        .getScheduler()
                        .runTask(plugin, () -> {
                            if (failure != null) {
                                error(sender, "History unavailable: " + failure.getMessage());
                            } else if (events.isEmpty()) {
                                error(sender, "No history for " + Labels.of(holder));
                            } else {
                                val now = System.currentTimeMillis();
                                reply(sender, "History of " + Labels.of(holder) + " (newest first):");
                                events.forEach(event ->
                                        sender.sendMessage(HistoryView.line(event, now, settings.topOrigins())));
                            }
                        }));
        return 1;
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

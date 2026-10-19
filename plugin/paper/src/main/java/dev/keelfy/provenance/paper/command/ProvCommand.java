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
import dev.keelfy.provenance.app.command.ProvService;
import dev.keelfy.provenance.app.command.Sender;
import dev.keelfy.provenance.app.port.OnlinePlayer;
import dev.keelfy.provenance.app.port.PlayerDirectory;
import dev.keelfy.provenance.paper.PaperSender;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.jspecify.annotations.Nullable;

/**
 * {@code /prov hand | block | player <name> | links [name] | history block | history player <name> [item]} and
 * {@code /prov web history ... | revoke <token>}: parses the command and checks permissions, {@link ProvService} does
 * the rest.
 *
 * @author keelfy
 */
@Singleton
@RequiredArgsConstructor(onConstructor = @__(@Inject))
public final class ProvCommand {

    public static final String PERMISSION = "provenance.admin";
    public static final String WEB_PERMISSION = "provenance.web";

    private static final String PLAYER_ARG = "player";
    private static final String ITEM_ARG = "item";
    private static final String TOKEN_ARG = "token";

    private final ProvService service;
    private final PlayerDirectory players;

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("prov")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .then(Commands.literal("hand").executes(run((sender, context) -> service.hand(sender))))
                .then(Commands.literal("block").executes(run((sender, context) -> service.block(sender))))
                .then(Commands.literal("player")
                        .then(Commands.argument(PLAYER_ARG, StringArgumentType.word())
                                .suggests(this::suggestPlayers)
                                .executes(run((sender, context) -> service.player(sender, name(context))))))
                .then(history(
                        (sender, context) -> service.historyBlock(sender),
                        (sender, context) -> service.historyPlayer(sender, name(context), item(context))))
                .then(Commands.literal("links")
                        .executes(run((sender, context) -> service.links(sender, null)))
                        .then(Commands.argument(PLAYER_ARG, StringArgumentType.word())
                                .suggests(this::suggestPlayers)
                                .executes(run((sender, context) -> service.links(sender, name(context))))))
                .then(Commands.literal("web")
                        .requires(source -> source.getSender().hasPermission(WEB_PERMISSION))
                        .then(history(
                                (sender, context) -> service.webHistoryBlock(sender),
                                (sender, context) -> service.webHistoryPlayer(sender, name(context), item(context))))
                        .then(Commands.literal("revoke")
                                .then(Commands.argument(TOKEN_ARG, StringArgumentType.word())
                                        .executes(run((sender, context) -> service.webRevoke(
                                                sender, StringArgumentType.getString(context, TOKEN_ARG)))))))
                .build();
    }

    private LiteralArgumentBuilder<CommandSourceStack> history(Action block, Action player) {
        return Commands.literal("history")
                .then(Commands.literal("block").executes(run(block)))
                .then(Commands.literal("player")
                        .then(Commands.argument(PLAYER_ARG, StringArgumentType.word())
                                .suggests(this::suggestPlayers)
                                .executes(run(player))
                                .then(Commands.argument(ITEM_ARG, StringArgumentType.word())
                                        .executes(run(player)))));
    }

    private interface Action {
        boolean run(Sender sender, CommandContext<CommandSourceStack> context);
    }

    private static Command<CommandSourceStack> run(Action action) {
        return context -> action.run(new PaperSender(context.getSource().getSender()), context) ? 1 : 0;
    }

    private static String name(CommandContext<CommandSourceStack> context) {
        return StringArgumentType.getString(context, PLAYER_ARG);
    }

    private static @Nullable String item(CommandContext<CommandSourceStack> context) {
        return context.getNodes().stream()
                        .anyMatch(node -> node.getNode().getName().equals(ITEM_ARG))
                ? StringArgumentType.getString(context, ITEM_ARG)
                : null;
    }

    private CompletableFuture<Suggestions> suggestPlayers(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        val prefix = builder.getRemainingLowerCase();
        players.online().stream()
                .map(OnlinePlayer::name)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .forEach(builder::suggest);
        return builder.buildFuture();
    }
}
